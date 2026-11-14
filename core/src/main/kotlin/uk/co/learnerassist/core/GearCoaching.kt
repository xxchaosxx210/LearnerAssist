package uk.co.learnerassist.core

import kotlin.math.abs

/** Instructor guidance, not factory shift points. This vehicle has five gears. */
data class TeachingProfile(val boundariesMph: List<Double> = listOf(10.0, 25.0, 35.0, 50.0)) {
    fun valid() = boundariesMph.size == 4 && boundariesMph.all { it.isFinite() && it in 3.0..90.0 } &&
        boundariesMph.zipWithNext().all { (a, b) -> b - a >= 3 }
}
data class CoachingConfig(
    val mode: DrivingMode = DrivingMode.BEGINNER, val upRpm: Int = 2000,
    val downRpm: Int = 1250, val intervalSeconds: Int = 15,
    val maxShiftRpm: Int = 3000, val profile: TeachingProfile = TeachingProfile(),
) {
    fun valid() = profile.valid() && upRpm in 1500..3500 && maxShiftRpm in 2000..4500 &&
        maxShiftRpm - upRpm >= 300 && downRpm in 900..1600 && intervalSeconds in 10..60
    val dwellMs get() = if (mode == DrivingMode.INTERMEDIATE) 5000L else 3000L
    val intervalMs get() = intervalSeconds.coerceAtLeast(10) * 1000L * if (mode == DrivingMode.INTERMEDIATE) 2 else 1
}
data class CoachingDecision(
    val advice: Advice = Advice.WAIT, val suggestedGear: Int? = null,
    val message: String = "Waiting for fresh readings",
    val detail: String = "No current gear suggestion.",
) {
    val speech: String? get() = if (advice in setOf(Advice.UP, Advice.DOWN, Advice.FIRST, Advice.PREPARE)) message else null
}

/** One decision feeds both the screen and voice. No diagnostic access. */
class Coach {
    private var previous: Reading? = null
    private var lastSecondAt: Long? = null
    private var pendingGear: Int? = null
    private var pendingSince = 0L
    private var lastPromptGear: Int? = null
    private var lastPromptAt = Long.MIN_VALUE / 2
    private var steadySince: Long? = null
    fun reset() {
        previous = null; lastSecondAt = null; pendingGear = null; pendingSince = 0
        lastPromptGear = null; lastPromptAt = Long.MIN_VALUE / 2; steadySince = null
    }
    fun evaluate(r: Reading, gear: Int?, calibration: Calibration, config: CoachingConfig, now: Long): CoachingDecision {
        val old = previous
        previous = r
        fun suppress(message: String, advice: Advice = Advice.UNKNOWN, detail: String = "No confident gear suggestion."): CoachingDecision {
            pendingGear = null; pendingSince = now; steadySince = null
            return CoachingDecision(advice, message = message, detail = detail)
        }
        if (!r.fresh(now) || !r.synchronized || r.rpm == null || r.kmh == null || !r.rpm.isFinite() ||
            !r.kmh.isFinite() || r.kmh < 0 || r.rpm !in 0.0..6500.0 || (r.load != null && (!r.load.isFinite() || r.load !in 0.0..100.0))) {
            lastSecondAt = null
            return suppress("Waiting for fresh readings", Advice.WAIT, "Stale or inconsistent readings: coaching paused.")
        }
        if (old?.source != r.source) { pendingGear = null; lastSecondAt = null; lastPromptGear = null }
        if (!config.valid()) return suppress("Check the teaching profile", detail = "Configure valid speed guides and RPM limits while parked.")
        if (config.mode == DrivingMode.INDEPENDENT) return suppress("Independent driving", Advice.OBSERVE, "Driving information remains available; routine coaching is off.")
        if (r.rpm < 500 || r.clutchDisengaged) {
            lastSecondAt = null
            return suppress("Waiting for reliable readings", detail = "Clutch, neutral or engine state is uncertain.")
        }
        if (r.kmh < 2) {
            lastSecondAt = null
            return suppress("Prepare for moving off", Advice.PREPARE, "The selected gear cannot be identified while stopped.")
        }
        if (!calibration.valid()) return suppress("Vehicle calibration needed", detail = "Capture known gears with an instructor/passenger before using suggestions.")
        if (gear != null && gear !in 1..5) return suppress("Waiting for reliable readings")
        val currentRatio = gear?.let { calibration.ratios[it] }
        if (gear != null && (currentRatio == null || abs(r.rpm / r.kmh - currentRatio) / currentRatio > 0.075)) {
            lastSecondAt = null
            return suppress("Waiting for reliable readings", detail = "RPM and speed do not agree with the current gear estimate.")
        }
        if (gear == 2) lastSecondAt = now else if (gear != null) lastSecondAt = null
        val dt = old?.let { (r.timestampMs - it.timestampMs) / 1000.0 } ?: 0.0
        if (old?.kmh == null || !old.fresh(now) || !old.synchronized || old.clutchDisengaged || old.source != r.source || dt !in 0.1..2.5)
            return suppress("Assessing driving conditions", Advice.WAIT)
        val acceleration = (r.kmh - old.kmh) / dt / 3.6
        if (acceleration !in -1.0..1.5) return suppress("Suggestion paused", detail = "Speed is changing quickly; follow your instructor and the road ahead.")
        val mph = r.mph!!
        val first = calibration.ratios[1]
        val second = calibration.ratios[2]
        val recentSecond = lastSecondAt?.let { now - it in 0..4000 } == true
        val plausibleSecond = second != null && r.kmh >= 8 && abs(r.rpm / r.kmh - second) / second <= 0.075
        val firstCandidate = first != null && plausibleSecond && (gear == 2 || (gear == null && recentSecond)) &&
            mph <= config.profile.boundariesMph.first() - 1 && r.rpm in 800.0..config.downRpm.toDouble() &&
            acceleration <= 0.1 && r.kmh * first in 900.0..minOf(2000, config.maxShiftRpm).toDouble()
        if (gear == null && !firstCandidate) return suppress("Waiting for reliable readings")
        if (r.load == null && !firstCandidate) return suppress("Suggestion paused", detail = "Engine load is unavailable; change advice is suppressed.")
        var target: Int? = if (firstCandidate) 1 else null
        var action = if (firstCandidate) Advice.FIRST else Advice.MAINTAIN
        if (!firstCandidate && gear != null) {
            val bandWantsUp = gear < 5 && mph >= config.profile.boundariesMph[gear - 1] + 1
            val bandWantsDown = gear > 2 && mph <= config.profile.boundariesMph[gear - 2] - 1
            when {
                gear < 5 && r.rpm >= config.upRpm && (bandWantsUp || r.rpm > config.maxShiftRpm) &&
                    r.load!! < 65 && acceleration >= -0.1 -> { target = gear + 1; action = Advice.UP }
                gear > 2 && acceleration <= 0.1 && ((bandWantsDown && r.rpm < config.upRpm) ||
                    (r.rpm < config.downRpm && r.load!! > 45)) -> { target = gear - 1; action = Advice.DOWN }
            }
            if (target == null && (bandWantsUp || bandWantsDown || r.rpm > config.maxShiftRpm || r.rpm < config.downRpm))
                return suppress("Suggestion paused", detail = "Speed guidance, engine load and revs do not yet support a change.")
        }
        if (target == null) {
            pendingGear = null
            if (steadySince == null) steadySince = now
            if (now - steadySince!! >= 4000) lastPromptGear = null
            return CoachingDecision(Advice.MAINTAIN, gear, "Maintain current gear", "The teaching profile and current readings support keeping this gear.")
        }
        steadySince = null
        val targetRatio = calibration.ratios[target]
            ?: return suppress("Calibrate gear $target", detail = "A target gear needs its own saved ratio before it can be suggested.")
        val predictedRpm = if (currentRatio != null) r.rpm * targetRatio / currentRatio else r.kmh * targetRatio
        val minimum = if (target == 1) 900.0 else if (action == Advice.UP) (config.downRpm + 100).toDouble() else 1000.0
        val maximum = if (target == 1) minOf(2000, config.maxShiftRpm).toDouble() else config.maxShiftRpm.toDouble()
        if (predictedRpm !in minimum..maximum)
            return suppress("Suggestion paused", detail = "Predicted revs in the next gear are outside the teaching limits.")
        if (pendingGear != target) { pendingGear = target; pendingSince = now }
        if (now - pendingSince < config.dwellMs)
            return CoachingDecision(Advice.WAIT, message = "Assessing gear suggestion", detail = "Waiting for the same conditions to remain stable.")
        if (lastPromptGear != target && now - lastPromptAt < config.intervalMs)
            return CoachingDecision(Advice.WAIT, message = "Coaching pause", detail = "Allowing time between different suggestions.")
        if (lastPromptGear != target) { lastPromptGear = target; lastPromptAt = now }
        val name = listOf("", "first", "second", "third", "fourth", "fifth")[target]
        return CoachingDecision(action, target, if (target == 1) "Consider first gear if continuing" else "Consider $name gear",
            if (target == 1) "If stopping, follow your instructor’s stopping routine."
            else "When appropriate for the road ahead. Instructor judgement takes priority.")
    }
}

/** Shared, testable voice timing. Same text as the displayed, confirmed decision. */
class VoicePromptPolicy {
    private var lastText: String? = null
    private var lastAt = Long.MIN_VALUE / 2
    fun reset() { lastText = null; lastAt = Long.MIN_VALUE / 2 }
    fun next(decision: CoachingDecision, enabled: Boolean, config: CoachingConfig, now: Long): String? {
        if (!enabled || config.mode == DrivingMode.INDEPENDENT || !config.valid()) return null
        val text = decision.speech ?: return null
        if (text == lastText || now - lastAt < config.intervalMs) return null
        lastText = text; lastAt = now
        return text
    }
}
