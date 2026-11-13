package uk.co.learnerassist.core

import kotlin.math.abs

enum class Source { SIMULATION, LIVE }
enum class DrivingMode { BEGINNER, INTERMEDIATE, INDEPENDENT }
data class Reading(
    val rpm: Double?, val kmh: Double?, val load: Double?, val timestampMs: Long,
    val source: Source, val clutchDisengaged: Boolean = false, val synchronized: Boolean = true,
) {
    val mph: Double? get() = kmh?.div(1.609344)
    fun fresh(nowMs: Long) = nowMs - timestampMs in 0..2500
}

data class Calibration(val ratios: Map<Int, Double> = emptyMap()) {
    fun valid(): Boolean = ratios.size >= 2 && ratios.all { (gear, ratio) -> gear in 1..6 && ratio.isFinite() && ratio in 10.0..300.0 } &&
        ratios.toSortedMap().values.zipWithNext().all { (a, b) -> a > b * 1.18 }
}

class GearEstimator {
    private var candidate: Int? = null
    private var since = 0L
    private var previous: Reading? = null
    fun reset() { candidate = null; previous = null; since = 0 }
    fun estimate(reading: Reading, calibration: Calibration, nowMs: Long): Int? {
        val prior = previous
        previous = reading
        fun unknown(): Int? { candidate = null; since = nowMs; return null }
        val rpm = reading.rpm ?: return unknown()
        val speed = reading.kmh ?: return unknown()
        if (!calibration.valid() || !reading.fresh(nowMs) || !reading.synchronized || reading.clutchDisengaged ||
            speed < 10 || rpm < 800 || rpm > 6500) return unknown()
        // Ratio instability suggests a shift, clutch slip or asynchronous samples.
        if (prior?.rpm != null && prior.kmh != null && prior.kmh >= 10 && reading.timestampMs > prior.timestampMs) {
            val oldRatio = prior.rpm / prior.kmh
            if (abs(rpm / speed - oldRatio) / oldRatio > 0.10) return unknown()
        }
        val matches = calibration.ratios.filter { abs(rpm / speed - it.value) / it.value <= 0.075 }.keys
        val match = matches.singleOrNull() ?: return unknown()
        if (candidate != match) { candidate = match; since = nowMs; return null }
        return if (nowMs - since >= 1500) match else null
    }
}

data class CalibrationReadiness(val message: String, val ratio: Double? = null) {
    val ready: Boolean get() = ratio != null
}

object CalibrationCapture {
    /** Quality floors for measurements, not recommended driving speeds. */
    fun minimumKmh(gear: Int) = if (gear == 1) 8.0 else 15.0
    fun assess(samples: List<Reading>, nowMs: Long, source: Source, gear: Int): CalibrationReadiness {
        fun waiting(message: String) = CalibrationReadiness(message)
        if (gear !in 1..6) return waiting("Select a forward gear from 1 to 6")
        val last = samples.lastOrNull() ?: return waiting("Waiting for live readings")
        if (!last.fresh(nowMs)) return waiting("Waiting for fresh readings")
        // Discard older history so a previous manoeuvre does not block a new capture.
        val window = samples.filter { last.timestampMs - it.timestampMs <= 6000 }
        if (window.any { it.source != source || it.clutchDisengaged || !it.synchronized ||
                it.rpm == null || it.kmh == null || !it.rpm.isFinite() || !it.kmh.isFinite() })
            return waiting("Readings uncertain; clutch must be fully engaged")
        if (window.zipWithNext().any { (a, b) -> b.timestampMs - a.timestampMs !in 100..1500 })
            return waiting("Waiting for continuous readings")
        val minimumRpm = if (gear == 1) 800 else 1000
        if (window.any { it.kmh!! < minimumKmh(gear) || it.rpm!! < minimumRpm || it.rpm > 6500 })
            return waiting(if (gear == 1) "First gear needs steady readings at 5 mph or more, clutch fully engaged"
                else "This gear needs steady readings at 9.3 mph or more, clutch fully engaged")
        val speeds = window.map { it.kmh!! }
        if (speeds.max() - speeds.min() > if (gear == 1) 1.0 else 3.0)
            return waiting("Speed is changing; wait for steady progress")
        val ratios = window.map { it.rpm!! / it.kmh!! }.sorted()
        val median = ratios[ratios.size / 2]
        if (median !in 10.0..300.0 || ratios.any { abs(it - median) / median > 0.035 })
            return waiting("RPM and speed do not form a stable ratio")
        val elapsed = last.timestampMs - window.first().timestampMs
        if (window.size < 6 || elapsed < 5000)
            return waiting("Collecting steady readings: ${elapsed.coerceAtLeast(0) / 1000}/5 seconds")
        return CalibrationReadiness("Ready — confirm the known gear and fully engaged clutch", median)
    }
    /** RPM per km/h; measured values, never factory ratios. */
    fun capture(samples: List<Reading>, nowMs: Long, source: Source, gear: Int): Double? =
        assess(samples, nowMs, source, gear).ratio
}

enum class Advice(val text: String) {
    UNKNOWN("Gear cannot be determined"), PREPARE("Prepare for moving off"),
    MAINTAIN("Maintain current gear"), UP("Consider changing up"), DOWN("Consider changing down"),
    FIRST("Consider first gear if continuing"),
    OBSERVE("Observe driving information"), WAIT("Waiting for fresh readings")
}
data class CoachingConfig(val mode: DrivingMode = DrivingMode.BEGINNER, val upRpm: Int = 2600, val downRpm: Int = 1250, val intervalSeconds: Int = 15)
class Coach {
    private var previous: Reading? = null
    private var pending = Advice.UNKNOWN
    private var pendingSince = 0L
    private var lastPrompt = Long.MIN_VALUE / 2
    private var lastAction: Advice? = null
    private var lastSecondAt: Long? = null
    fun reset() { previous = null; pending = Advice.UNKNOWN; pendingSince = 0; lastPrompt = Long.MIN_VALUE / 2; lastAction = null; lastSecondAt = null }
    fun advise(r: Reading, gear: Int?, calibration: Calibration, config: CoachingConfig, now: Long): Advice {
        val old = previous
        previous = r
        fun suppress(advice: Advice): Advice {
            pending = advice; pendingSince = now
            return advice
        }
        if (!r.fresh(now) || !r.synchronized || r.rpm == null || r.kmh == null ||
            !r.rpm.isFinite() || !r.kmh.isFinite()) {
            lastSecondAt = null
            return suppress(Advice.WAIT)
        }
        if (old?.source != r.source) lastSecondAt = null
        if (config.mode == DrivingMode.INDEPENDENT) return suppress(Advice.OBSERVE)
        if (r.rpm < 500 || r.clutchDisengaged) { lastSecondAt = null; return suppress(Advice.UNKNOWN) }
        if (r.kmh < 2) { lastSecondAt = null; return suppress(Advice.PREPARE) }
        if (gear == 2) lastSecondAt = now
        else if (gear != null) lastSecondAt = null
        val dt = if (old == null) 0.0 else (r.timestampMs - old.timestampMs) / 1000.0
        if (dt !in 0.1..2.5 || old?.kmh == null || !old.fresh(now) || !old.synchronized || old.source != r.source)
            return suppress(if (gear == null) Advice.UNKNOWN else Advice.MAINTAIN)
        val acceleration = (r.kmh - old.kmh) / dt / 3.6
        val secondRatio = calibration.ratios[2]
        val firstRatio = calibration.ratios[1]
        // A recent second-gear observation may support a conditional prompt below the
        // estimator's speed floor; it never becomes a claimed current gear.
        val recentSecond = lastSecondAt?.let { now - it in 0..4000 } == true
        val secondStillPlausible = secondRatio != null && r.kmh >= 8 &&
            abs(r.rpm / r.kmh - secondRatio) / secondRatio <= 0.075
        val firstCandidate = calibration.valid() && firstRatio != null && secondStillPlausible &&
            (gear == 2 || (gear == null && recentSecond)) && r.kmh <= 12 &&
            r.rpm in 800.0..config.downRpm.toDouble() && acceleration in -1.0..0.1 &&
            r.kmh * firstRatio in 900.0..1800.0
        if (!firstCandidate && (gear == null || r.load == null)) return suppress(Advice.UNKNOWN)
        val desired = when {
            firstCandidate -> Advice.FIRST
            r.rpm > config.upRpm && r.load!! < 65 && acceleration in -0.1..1.5 && calibration.ratios.containsKey(gear!! + 1) -> Advice.UP
            r.rpm < config.downRpm && r.kmh > 12 && r.load!! > 45 && acceleration < 0.3 && gear!! > 2 -> Advice.DOWN
            else -> Advice.MAINTAIN
        }
        if (desired != pending) { pending = desired; pendingSince = now }
        if (desired == Advice.MAINTAIN) {
            if (now - pendingSince >= 4000) lastAction = null
            return desired
        }
        val dwell = if (config.mode == DrivingMode.INTERMEDIATE) 5000 else 3000
        val interval = config.intervalSeconds.coerceAtLeast(10) * 1000L * if (config.mode == DrivingMode.INTERMEDIATE) 2 else 1
        // Hold a confirmed suggestion briefly so it can be read, while conditions still agree.
        if (lastAction == desired && now - lastPrompt < 4000) return desired
        if (now - pendingSince < dwell || now - lastPrompt < interval || lastAction == desired)
            return if (gear == null) Advice.UNKNOWN else Advice.MAINTAIN
        lastPrompt = now
        lastAction = desired
        return desired
    }
}

enum class Scenario(val title: String) {
    MOVING_OFF("Moving away"), ACCELERATING("Through the gears"), STEADY("Steady at 30 mph"),
    JUNCTION("Slowing for a junction"), STOPPING("Approaching a stop"), SECOND_TO_FIRST("Second to first · slow progress"),
    STRUGGLING("Low RPM, high load"), CLUTCH("Clutch disengaged")
}
object Simulator {
    // Educational fixture only. Kept separate from the user's live calibration.
    val calibration = Calibration(mapOf(1 to 120.0, 2 to 78.0, 3 to 53.0, 4 to 40.0, 5 to 32.0))
    fun sample(scenario: Scenario, elapsed: Double, now: Long): Reading {
        val t = elapsed % 30
        val (speed, ratio, load) = when (scenario) {
            Scenario.MOVING_OFF -> Triple((t * 1.4).coerceAtMost(23.0), 120.0, 40.0)
            Scenario.ACCELERATING -> { val speed = 12 + t * 2; Triple(speed, when { speed < 24 -> 120.0; speed < 38 -> 78.0; speed < 54 -> 53.0; else -> 40.0 }, 45.0) }
            Scenario.STEADY -> Triple(48.28, 53.0, 30.0)
            Scenario.JUNCTION -> Triple((48 - t * 1.1).coerceAtLeast(15.0), 53.0, 18.0)
            Scenario.STOPPING -> Triple((30 - t * 1.5).coerceAtLeast(0.0), 78.0, 15.0)
            Scenario.SECOND_TO_FIRST -> {
                val speed = when { t < 5 -> 18.0; t < 10 -> 18.0 - (t - 5) * 1.3; else -> 11.5 }
                Triple(speed, 78.0, 20.0)
            }
            Scenario.STRUGGLING -> Triple(26.0, 40.0, 78.0)
            Scenario.CLUTCH -> Triple(40.0, 23.0, 12.0)
        }
        return Reading((speed * ratio).coerceAtLeast(850.0), speed, load, now, Source.SIMULATION, scenario == Scenario.CLUTCH)
    }
}
