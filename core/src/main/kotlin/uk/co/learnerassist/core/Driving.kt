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
        if (speeds.max() - speeds.min() > if (gear == 1) 4.0 else 3.0)
            return waiting("Speed is changing too much; waiting for gentler progress")
        val ratios = window.map { it.rpm!! / it.kmh!! }.sorted()
        val median = ratios[ratios.size / 2]
        // PID 0D reports whole km/h. Near 5 mph, a half-step alone is about 6%
        // of speed: a flat percentage test rejects a consistent engaged gear.
        // Keep the 3.5% RPM tolerance and add only the speed quantisation term.
        val fittedRatio = if (gear == 1) {
            // Find the common ratio range consistent with every sample's bounded
            // sensor error, then fit RPM against speed within that range. A raw
            // median can be biased when speed alternates between adjacent integers.
            val lower = window.maxOf { it.rpm!! / (it.kmh!! * 1.035 + 0.5) }
            val upper = window.minOf { it.rpm!! / (it.kmh!! * 0.965 - 0.5) }
            if (lower > upper) return waiting("RPM and speed do not form a stable ratio")
            val fitted = window.sumOf { it.rpm!! * it.kmh!! } / window.sumOf { it.kmh!! * it.kmh!! }
            fitted.coerceIn(lower, upper)
        } else median
        val unstable = window.any { sample ->
            val rpm = sample.rpm!!
            val predictedRpm = fittedRatio * sample.kmh!!
            val speedStepAllowance = if (gear == 1) fittedRatio * 0.5 else 0.0
            abs(rpm - predictedRpm) > predictedRpm * 0.035 + speedStepAllowance
        }
        if (fittedRatio !in 10.0..300.0 || unstable)
            return waiting("RPM and speed do not form a stable ratio")
        val elapsed = last.timestampMs - window.first().timestampMs
        if (window.size < 6 || elapsed < 5000)
            return waiting("Collecting steady readings: ${elapsed.coerceAtLeast(0) / 1000}/5 seconds")
        return CalibrationReadiness("Ready — confirm the known gear and fully engaged clutch", fittedRatio)
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
enum class Scenario(val title: String) {
    MOVING_OFF("Moving away"), ACCELERATING("Through the gears"), STEADY("Steady at 30 mph"),
    JUNCTION("Slowing for a junction"), STOPPING("Approaching a stop"), SECOND_TO_FIRST("Second to first · slow progress"),
    STRUGGLING("Low RPM, high load"), CLUTCH("Clutch disengaged"),
    SUGGEST_THIRD("Consider third gear"), HIGH_LOAD("High load · suggestion paused")
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
            Scenario.SUGGEST_THIRD -> Triple(26 * 1.609344, 78.0, 28.0)
            Scenario.HIGH_LOAD -> Triple(26 * 1.609344, 78.0, 86.0)
        }
        return Reading((speed * ratio).coerceAtLeast(850.0), speed, load, now, Source.SIMULATION, scenario == Scenario.CLUTCH)
    }
}
