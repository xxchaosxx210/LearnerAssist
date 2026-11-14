package uk.co.learnerassist.core

import org.junit.Assert.*
import org.junit.Test

class GearCoachingTest {
    private val calibration = Calibration(mapOf(1 to 126.9, 2 to 65.26, 3 to 40.37, 4 to 29.46, 5 to 22.96))
    private fun reading(t: Long, mph: Double = 26.0, gear: Int = 2, load: Double? = 28.0) =
        Reading(mph * 1.609344 * calibration.ratios.getValue(gear), mph * 1.609344, load, t, Source.SIMULATION)
    private fun results(gear: Int? = 2, config: CoachingConfig = CoachingConfig(), ratios: Calibration = calibration,
        count: Int = 20, modify: (Reading) -> Reading = { it }): List<CoachingDecision> {
        val coach = Coach()
        return (0..count).map { i -> val t = i * 500L; coach.evaluate(modify(reading(t)), gear, ratios, config, t) }
    }
    @Test fun `default profile represents the instructor five gear ranges and rpm band`() {
        val config = CoachingConfig()
        assertEquals(listOf(10.0, 25.0, 35.0, 50.0), config.profile.boundariesMph)
        assertEquals(2000, config.upRpm); assertEquals(3000, config.maxShiftRpm); assertTrue(config.valid())
    }
    @Test fun `26 mph in second suggests third with exact matching voice text after dwell`() {
        val decisions = results()
        assertTrue(decisions.take(4).all { it.suggestedGear == null })
        val suggestion = decisions.last()
        assertEquals(3, suggestion.suggestedGear); assertEquals(Advice.UP, suggestion.advice)
        assertEquals("Consider third gear", suggestion.message); assertEquals(suggestion.message, suggestion.speech)
    }
    @Test fun `suggestion stays visible while conditions agree rather than flashing briefly`() {
        assertTrue(results(count = 60).drop(4).all { it.suggestedGear == 3 })
    }
    @Test fun `intermediate requires a longer stable period than beginner`() {
        assertEquals(3, results(count = 4).last().suggestedGear)
        assertNull(results(config = CoachingConfig(mode = DrivingMode.INTERMEDIATE), count = 4).last().suggestedGear)
        assertEquals(3, results(config = CoachingConfig(mode = DrivingMode.INTERMEDIATE), count = 8).last().suggestedGear)
    }
    @Test fun `independent suppresses suggested gear and all routine speech`() {
        results(config = CoachingConfig(mode = DrivingMode.INDEPENDENT)).forEach {
            assertNull(it.suggestedGear); assertNull(it.speech); assertEquals(Advice.OBSERVE, it.advice)
        }
    }
    @Test fun `high load missing load and deceleration suppress upshift`() {
        assertTrue(results(modify = { it.copy(load = 86.0) }).all { it.suggestedGear == null })
        assertTrue(results(modify = { it.copy(load = null) }).all { it.suggestedGear == null })
        val coach = Coach()
        (0..12).forEach { i ->
            val r = reading(i * 500L, 30 - i * 0.25)
            assertNotEquals(Advice.UP, coach.evaluate(r, 2, calibration, CoachingConfig(), r.timestampMs).advice)
        }
    }
    @Test fun `unknown clutch stale unsynchronised invalid and mixed source data suppress suggestions`() {
        assertTrue(results(gear = null).all { it.suggestedGear == null })
        for (transform in listOf<(Reading) -> Reading>(
            { it.copy(clutchDisengaged = true) }, { it.copy(synchronized = false) }, { it.copy(rpm = null) },
            { it.copy(kmh = null) }, { it.copy(rpm = Double.NaN) }, { it.copy(kmh = Double.POSITIVE_INFINITY) },
            { it.copy(load = Double.NaN) }, { it.copy(load = 101.0) }, { it.copy(timestampMs = it.timestampMs - 4000) },
            { it.copy(source = if (it.timestampMs % 1000 == 0L) Source.LIVE else Source.SIMULATION) },
        )) assertTrue(results(modify = transform).all { it.suggestedGear == null })
    }
    @Test fun `stopped and engine off never claim first gear`() {
        val coach = Coach()
        val stopped = reading(0, 0.0).copy(rpm = 850.0)
        val prepare = coach.evaluate(stopped, null, calibration, CoachingConfig(), 0)
        assertEquals(Advice.PREPARE, prepare.advice); assertNull(prepare.suggestedGear)
        assertEquals("Prepare for moving off", prepare.speech)
        assertNull(coach.evaluate(stopped.copy(rpm = 0.0), null, calibration, CoachingConfig(), 0).speech)
    }
    @Test fun `a range never overrides predicted lugging revs`() {
        assertTrue(results(gear = 1, modify = { reading(it.timestampMs, 11.2, 1) }).all { it.suggestedGear == null })
    }
    @Test fun `missing target ratio and invalid calibration suppress suggestions`() {
        assertTrue(results(ratios = Calibration(calibration.ratios - 3)).all { it.suggestedGear == null })
        assertTrue(results(ratios = Calibration()).all { it.suggestedGear == null })
        assertTrue(results(gear = 3).all { it.suggestedGear == null })
    }
    @Test fun `upshift starts at the guide while reverse downshift keeps a one mph dead band`() {
        assertTrue(results(modify = { reading(it.timestampMs, 24.8) }).all { it.advice != Advice.UP && it.suggestedGear != 3 })
        assertEquals(3, results(modify = { reading(it.timestampMs, 25.1) }).last().suggestedGear)
        assertTrue(results(gear = 3, modify = { reading(it.timestampMs, 24.5, 3) }).all { it.advice != Advice.DOWN })
        assertEquals(2, results(gear = 3, modify = { reading(it.timestampMs, 23.8, 3) }).last().suggestedGear)
    }
    @Test fun `slowing or steady low speed third can suggest second if predicted revs fit`() {
        val decision = results(gear = 3, modify = { reading(it.timestampMs, 20.0, 3) }).last()
        assertEquals(2, decision.suggestedGear); assertEquals("Consider second gear", decision.message)
    }
    @Test fun `downshift does not suggest revs beyond the configured upper band`() {
        val ratios = Calibration(mapOf(1 to 150.0, 2 to 100.0, 3 to 65.0))
        val coach = Coach()
        (0..12).forEach { i ->
            val t = i * 500L
            val r = reading(t, 20.0).copy(rpm = 20 * 1.609344 * 65)
            assertNull(coach.evaluate(r, 3, ratios, CoachingConfig(upRpm = 2700), t).suggestedGear)
        }
    }
    @Test fun `first at slow progress is conditional and does not need high load`() {
        val decision = results(modify = { reading(it.timestampMs, 8.0, 2, null) }).last()
        assertEquals(1, decision.suggestedGear); assertEquals(Advice.FIRST, decision.advice)
        assertEquals("Consider first gear if continuing", decision.message)
    }
    @Test fun `expired low speed second history never becomes an invented current gear`() {
        val coach = Coach()
        val ratios = Calibration(mapOf(1 to 150.0, 2 to 100.0, 3 to 70.0))
        for (i in 0..4) { val t = i * 500L; coach.evaluate(Reading(1000.0, 10.0, 20.0, t, Source.SIMULATION), 2, ratios, CoachingConfig(), t) }
        val decisions = (1..12).map { i -> val t = 2000 + i * 500L
            coach.evaluate(Reading(980.0, 9.8, 20.0, t, Source.SIMULATION), null, ratios, CoachingConfig(), t) }
        assertTrue(decisions.any { it.suggestedGear == 1 }); assertNull(decisions.last().suggestedGear)
    }
    @Test fun `fifth never produces a sixth gear recommendation`() {
        val decisions = results(gear = 5, modify = { reading(it.timestampMs, 70.0, 5) })
        assertTrue(decisions.all { it.suggestedGear == 5 || it.suggestedGear == null })
    }
    @Test fun `maintaining a reliable gear is silent`() {
        val decision = results(gear = 3, modify = { reading(it.timestampMs, 32.0, 3) }).last()
        assertEquals(3, decision.suggestedGear); assertEquals(Advice.MAINTAIN, decision.advice); assertNull(decision.speech)
    }
    @Test fun `invalid speed and rpm profiles fail closed`() {
        for (config in listOf(CoachingConfig(profile = TeachingProfile(listOf(10.0, 9.0, 35.0, 50.0))),
            CoachingConfig(profile = TeachingProfile(listOf(Double.NaN, 25.0, 35.0, 50.0))),
            CoachingConfig(upRpm = 2900, maxShiftRpm = 3000), CoachingConfig(intervalSeconds = 0)))
            assertTrue(results(config = config).all { it.suggestedGear == null })
    }
    @Test fun `uncertainty and reset require a fresh stable candidate`() {
        val coach = Coach()
        (0..10).forEach { val t = it * 500L; coach.evaluate(reading(t), 2, calibration, CoachingConfig(), t) }
        assertNull(coach.evaluate(reading(5500).copy(clutchDisengaged = true), null, calibration, CoachingConfig(), 5500).suggestedGear)
        (12..15).forEach { val t = it * 500L; assertNull(coach.evaluate(reading(t), 2, calibration, CoachingConfig(), t).suggestedGear) }
        assertEquals(3, coach.evaluate(reading(8000), 2, calibration, CoachingConfig(), 8000).suggestedGear)
        coach.reset(); assertNull(coach.evaluate(reading(10500), 2, calibration, CoachingConfig(), 10500).suggestedGear)
    }
    @Test fun `different suggestions respect minimum prompt intervals`() {
        val coach = Coach()
        val config = CoachingConfig(intervalSeconds = 15)
        for (i in 0..10) { val t = i * 500L; coach.evaluate(reading(t), 2, calibration, config, t) }
        val later = (11..40).map { val t = it * 500L; coach.evaluate(reading(t, 20.0, 3), 3, calibration, config, t) }
        assertTrue(later.take(20).all { it.suggestedGear != 2 }); assertEquals(2, later.last().suggestedGear)
    }
    @Test fun `voice uses approved displayed text without adding a second timing gate`() {
        val voice = VoicePromptPolicy()
        val decision = results().last()
        assertNull(voice.next(decision, false, CoachingConfig()))
        assertNull(voice.next(decision, true, CoachingConfig(mode = DrivingMode.INDEPENDENT)))
        assertEquals(decision.message, voice.next(decision, true, CoachingConfig()))
        assertNull(voice.next(decision, true, CoachingConfig()))
        val second = CoachingDecision(Advice.DOWN, 2, "Consider second gear")
        assertEquals(second.message, voice.next(second, true, CoachingConfig()))
        assertNull(voice.next(second, true, CoachingConfig()))
        assertNull(voice.next(CoachingDecision(), true, CoachingConfig()))
        assertNull(voice.next(CoachingDecision(Advice.MAINTAIN, 3), true, CoachingConfig()))
        voice.reset(); assertEquals(decision.message, voice.next(decision, true, CoachingConfig()))
    }
    @Test fun `just over fifty in fourth offers fifth within two seconds once conditions support it`() {
        val coach = Coach(); val voice = VoicePromptPolicy()
        val decisions = (0..8).map { i -> val t = i * 500L
            val decision = coach.evaluate(reading(t, 81.0 / 1.609344, 4), 4, calibration, CoachingConfig(), t)
            t to decision }
        val first = decisions.first { it.second.suggestedGear == 5 }
        assertEquals(2000L, first.first)
        assertEquals("Consider fifth gear", voice.next(first.second, true, CoachingConfig()))
        assertTrue(decisions.take(4).all { it.second.suggestedGear == null })
    }
    @Test fun `a recent fourth prompt does not cause a fifteen second wait for fifth or its voice`() {
        val coach = Coach(); val voice = VoicePromptPolicy(); var fourthSpoken = false
        for (i in 0..8) { val t = i * 500L
            val r = if (t <= 2000) reading(t, 45.0, 3) else reading(t, 46.0, 4)
            val decision = coach.evaluate(r, if (t <= 2000) 3 else 4, calibration, CoachingConfig(), t)
            if (voice.next(decision, true, CoachingConfig()) == "Consider fourth gear") fourthSpoken = true
        }
        assertTrue(fourthSpoken)
        val firstFifth = (9..20).map { i -> val t = i * 500L
            t to coach.evaluate(reading(t, 81.0 / 1.609344, 4), 4, calibration, CoachingConfig(), t)
        }.first { it.second.suggestedGear == 5 }
        assertTrue(firstFifth.first <= 7000)
        assertEquals("Consider fifth gear", voice.next(firstFifth.second, true, CoachingConfig()))
    }
    @Test fun `responsive defaults keep intermediate less frequent and high load fifth suppressed`() {
        assertEquals(1500L, CoachingConfig().dwellMs); assertEquals(3000L, CoachingConfig().intervalMs)
        val intermediate = CoachingConfig(mode = DrivingMode.INTERMEDIATE)
        assertEquals(3000L, intermediate.dwellMs); assertEquals(6000L, intermediate.intervalMs)
        assertTrue(results(gear = 4, modify = { reading(it.timestampMs, 51.0, 4, 86.0) }).all { it.suggestedGear == null })
    }
    @Test fun `all scenarios are simulated and specific third example runs the full pipeline`() {
        for (scenario in Scenario.entries) assertEquals(Source.SIMULATION, Simulator.sample(scenario, 5.0, 5000).source)
        val estimator = GearEstimator(); val coach = Coach()
        val decisions = (0..20).map { val t = it * 500L; val r = Simulator.sample(Scenario.SUGGEST_THIRD, it / 2.0, t)
            coach.evaluate(r, estimator.estimate(r, Simulator.calibration, t), Simulator.calibration, CoachingConfig(), t) }
        assertEquals(3, decisions.last().suggestedGear)
    }
    @Test fun `live screenshot first gear at 2037 rpm 17 kmh and 17 percent load suggests second`() {
        val estimator = GearEstimator(); val coach = Coach(); val voice = VoicePromptPolicy()
        val decisions = (0..12).map { i -> val t = i * 500L
            val r = Reading(2037.0, 17.0, 17.0, t, Source.LIVE)
            val estimated = estimator.estimate(r, calibration, t)
            if (t >= 1500) assertEquals(1, estimated)
            coach.evaluate(r, estimated, calibration, CoachingConfig(), t)
        }
        val decision = decisions.last()
        assertEquals(2, decision.suggestedGear); assertEquals("Consider second gear", decision.message)
        assertEquals(decision.message, voice.next(decision, true, CoachingConfig()))
    }
    @Test fun `first to second exception excludes higher demand and brisk acceleration`() {
        for (load in listOf(26.0, 40.0, 86.0)) {
            val coach = Coach()
            (0..12).forEach { i -> val t = i * 500L
                assertNull(coach.evaluate(Reading(2037.0, 17.0, load, t, Source.LIVE), 1, calibration, CoachingConfig(), t).suggestedGear)
            }
        }
        val coach = Coach()
        (0..8).forEach { i -> val t = i * 500L; val kmh = 17.0 + i * 1.0
            val r = Reading(kmh * 126.9, kmh, 17.0, t, Source.LIVE)
            val decision = coach.evaluate(r, 1, calibration, CoachingConfig(), t)
            if (kmh * calibration.ratios.getValue(2) < 1350) assertNull(decision.suggestedGear)
        }
    }
    @Test fun `light load never permits predicted second below a thousand or lowers other gear floors`() {
        val coach = Coach(); val ratios = Calibration(calibration.ratios + (2 to 55.0))
        (0..12).forEach { i -> val t = i * 500L
            assertNull(coach.evaluate(Reading(2037.0, 17.0, 17.0, t, Source.LIVE), 1, ratios, CoachingConfig(), t).suggestedGear)
        }
        val config = CoachingConfig(upRpm = 1800, profile = TeachingProfile(listOf(10.0, 15.0, 35.0, 50.0)))
        assertTrue(results(gear = 2, config = config, modify = { reading(it.timestampMs, 19.0, 2, 17.0) }).all { it.suggestedGear == null })
    }
    @Test fun `light load first to second simulator uses the real decision pipeline`() {
        val estimator = GearEstimator(); val coach = Coach()
        val decisions = (0..12).map { i -> val t = i * 500L
            val r = Simulator.sample(Scenario.FIRST_TO_SECOND, i / 2.0, t)
            coach.evaluate(r, estimator.estimate(r, Simulator.calibration, t), Simulator.calibration, CoachingConfig(), t)
        }
        assertEquals(2, decisions.last().suggestedGear)
    }
}
