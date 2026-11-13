package uk.co.learnerassist.core

import org.junit.Assert.*
import org.junit.Test

class DrivingTest {
    private val calibration = Simulator.calibration
    private fun reading(t: Long, rpm: Double = 2120.0, speed: Double = 40.0) = Reading(rpm, speed, 30.0, t, Source.SIMULATION)
    @Test fun `gear estimate requires stable calibrated ratio`() {
        val estimator = GearEstimator()
        assertNull(estimator.estimate(reading(0), calibration, 0))
        assertNull(estimator.estimate(reading(1000), calibration, 1000))
        assertEquals(3, estimator.estimate(reading(2000), calibration, 2000))
    }
    @Test fun `every demo gear estimates above minimum speed`() {
        calibration.ratios.forEach { (gear, ratio) ->
            val estimator = GearEstimator()
            estimator.estimate(reading(0, 25 * ratio, 25.0), calibration, 0)
            assertEquals(gear, estimator.estimate(reading(2000, 25 * ratio, 25.0), calibration, 2000))
        }
    }
    @Test fun `stationary low speed clutch stale and missing sensors remain unknown`() {
        listOf(reading(0, 850.0, 0.0), reading(0, 1000.0, 5.0), reading(0).copy(clutchDisengaged = true),
            reading(0).copy(synchronized = false), reading(0).copy(rpm = null), reading(0).copy(kmh = null)).forEach {
            assertNull(GearEstimator().estimate(it, calibration, 0))
        }
        assertNull(GearEstimator().estimate(reading(0), calibration, 3000))
    }
    @Test fun `missing unordered duplicate and invalid calibration are rejected`() {
        listOf(Calibration(), Calibration(mapOf(1 to 120.0)), Calibration(mapOf(1 to 50.0, 2 to 80.0)),
            Calibration(mapOf(1 to Double.NaN, 2 to 40.0)), Calibration(mapOf(1 to 50.0, 2 to 50.0))).forEach { assertFalse(it.valid()) }
    }
    @Test fun `unmatched ratios and fluctuations return unknown`() {
        val estimator = GearEstimator()
        estimator.estimate(reading(0), calibration, 0)
        assertNull(estimator.estimate(reading(2000, 2500.0), calibration, 2000))
        assertNull(estimator.estimate(reading(2500, 2120.0), calibration, 2500))
    }
    private fun samples() = (0..10).map { reading(it * 500L).copy(source = Source.LIVE) }
    @Test fun `calibration captures stable known gear over five seconds`() {
        assertEquals(53.0, CalibrationCapture.capture(samples(), 5000, Source.LIVE, 3)!!, 0.001)
    }
    @Test fun `calibration rejects insufficient unstable stale mixed or clutch samples`() {
        assertNull(CalibrationCapture.capture(samples().take(4), 5000, Source.LIVE, 3))
        assertNull(CalibrationCapture.capture(samples(), 9000, Source.LIVE, 3))
        assertNull(CalibrationCapture.capture(samples().map { it.copy(kmh = 5.0) }, 5000, Source.LIVE, 3))
        assertNull(CalibrationCapture.capture(samples().mapIndexed { i, r -> if (i == 4) r.copy(rpm = 4000.0) else r }, 5000, Source.LIVE, 3))
        assertNull(CalibrationCapture.capture(samples().mapIndexed { i, r -> if (i == 4) r.copy(source = Source.SIMULATION) else r }, 5000, Source.LIVE, 3))
        assertNull(CalibrationCapture.capture(samples().map { it.copy(clutchDisengaged = true) }, 5000, Source.LIVE, 3))
    }
    @Test fun `coaching considers load and trend and waits before suggesting up`() {
        val coach = Coach()
        val results = (0..10).map { t -> coach.advise(reading(t * 500L, 3000.0, 3000.0 / 53), 3, calibration, CoachingConfig(), t * 500L) }
        assertFalse(results.take(6).contains(Advice.UP))
        assertTrue(results.contains(Advice.UP))
        assertTrue(results.drop(7).all { it == Advice.UP })
    }
    @Test fun `high load deceleration uncertain gear and independent mode suppress upshift`() {
        val config = CoachingConfig()
        for (kind in 0..3) {
            val coach = Coach()
            val results = (0..20).map { t ->
                val r = reading(t * 500L, 3000.0, if (kind == 1) 60 - t * 0.5 else 56.6).copy(load = if (kind == 0) 90.0 else 30.0)
                coach.advise(r, if (kind == 2) null else 3, calibration, if (kind == 3) config.copy(mode = DrivingMode.INDEPENDENT) else config, t * 500L)
            }
            assertFalse(results.contains(Advice.UP))
        }
    }
    @Test fun `low rpm under load can suggest down after dwell`() {
        val coach = Coach()
        val results = (0..10).map { t -> coach.advise(reading(t * 500L, 1100.0, 27.5).copy(load = 80.0), 4, calibration, CoachingConfig(), t * 500L) }
        assertTrue(results.contains(Advice.DOWN))
    }
    @Test fun `no advice on stale data or engine off and stationary gear stays unknown`() {
        val coach = Coach()
        assertEquals(Advice.WAIT, coach.advise(reading(0), 3, calibration, CoachingConfig(), 3000))
        assertEquals(Advice.UNKNOWN, coach.advise(reading(0, 0.0, 0.0), null, calibration, CoachingConfig(), 0))
        assertEquals(Advice.PREPARE, coach.advise(reading(0, 850.0, 0.0), null, calibration, CoachingConfig(), 0))
    }
    @Test fun `all scenarios remain unmistakably simulation without transport`() {
        Scenario.entries.forEach { scenario ->
            (0..30).forEach { second ->
                val r = Simulator.sample(scenario, second.toDouble(), second * 1000L)
                assertEquals(Source.SIMULATION, r.source)
                assertTrue(r.rpm!! >= 0); assertTrue(r.kmh!! >= 0)
            }
        }
    }
    @Test fun `slow progress in second suggests first without high engine load`() {
        val coach = Coach()
        val results = (0..12).map { t ->
            coach.advise(reading(t * 500L, 897.0, 11.5).copy(load = 15.0), 2, calibration, CoachingConfig(), t * 500L)
        }
        assertFalse(results.take(6).contains(Advice.FIRST))
        assertTrue(results.contains(Advice.FIRST))
    }
    @Test fun `recent second can support conditional advice without claiming a low speed gear`() {
        val ratios = Calibration(mapOf(1 to 150.0, 2 to 100.0, 3 to 70.0))
        val estimator = GearEstimator()
        val coach = Coach()
        for (t in 0..4) {
            val r = reading(t * 500L, 1000.0, 10.0)
            val gear = estimator.estimate(r, ratios, r.timestampMs)
            coach.advise(r, gear, ratios, CoachingConfig(), r.timestampMs)
        }
        val results = (1..8).map { t ->
            val r = reading(2000 + t * 500L, 980.0, 9.8)
            val gear = estimator.estimate(r, ratios, r.timestampMs)
            assertNull(gear)
            coach.advise(r, gear, ratios, CoachingConfig(), r.timestampMs)
        }
        assertTrue(results.contains(Advice.FIRST))
        val expired = reading(6500, 980.0, 9.8)
        assertEquals(Advice.UNKNOWN, coach.advise(expired, null, ratios, CoachingConfig(), 6500))
    }
    @Test fun `first advice is suppressed with no confirmed second or mismatching ratio`() {
        for (gear in listOf(null, 3)) {
            val coach = Coach()
            val results = (0..12).map { t -> coach.advise(reading(t * 500L, 897.0, 11.5), gear, calibration, CoachingConfig(), t * 500L) }
            assertFalse(results.contains(Advice.FIRST))
        }
        val coach = Coach()
        coach.advise(reading(0, 897.0, 11.5), 2, calibration, CoachingConfig(), 0)
        for (t in 1..12) assertNotEquals(Advice.FIRST,
            coach.advise(reading(t * 500L, 1200.0, 9.0), null, calibration, CoachingConfig(), t * 500L))
    }
    @Test fun `first advice requires both calibrated gears and a modest predicted first rpm`() {
        for (ratios in listOf(Calibration(mapOf(2 to 78.0, 3 to 53.0)), Calibration(mapOf(1 to 250.0, 2 to 78.0)))) {
            val coach = Coach()
            val results = (0..12).map { t -> coach.advise(reading(t * 500L, 897.0, 11.5), 2, ratios, CoachingConfig(), t * 500L) }
            assertFalse(results.contains(Advice.FIRST))
        }
    }
    @Test fun `first advice is suppressed during acceleration braking clutch uncertainty or independent mode`() {
        for (kind in 0..3) {
            val coach = Coach()
            val results = (0..12).map { t ->
                val speed = when (kind) { 0 -> 8.0 + t * 0.35; 1 -> 40.0 - t * 3; else -> 11.5 }
                val r = reading(t * 500L, speed * 78, speed).copy(clutchDisengaged = kind == 2)
                coach.advise(r, 2, calibration, CoachingConfig(mode = if (kind == 3) DrivingMode.INDEPENDENT else DrivingMode.BEGINNER), t * 500L)
            }
            assertFalse(results.contains(Advice.FIRST))
        }
    }
    @Test fun `uncertain interval restarts first suggestion dwell`() {
        val coach = Coach()
        for (t in 0..4) coach.advise(reading(t * 500L, 897.0, 11.5), 2, calibration, CoachingConfig(), t * 500L)
        coach.advise(reading(2500, 897.0, 11.5).copy(synchronized = false), null, calibration, CoachingConfig(), 2500)
        for (t in 6..12) assertNotEquals(Advice.FIRST,
            coach.advise(reading(t * 500L, 897.0, 11.5), 2, calibration, CoachingConfig(), t * 500L))
        assertEquals(Advice.FIRST, coach.advise(reading(6500, 897.0, 11.5), 2, calibration, CoachingConfig(), 6500))
    }
    @Test fun `first suggestion does not repeat while same conditions continue`() {
        val coach = Coach()
        val results = (0..60).map { t -> coach.advise(reading(t * 500L, 897.0, 11.5), 2, calibration, CoachingConfig(), t * 500L) }
        assertTrue(results.contains(Advice.FIRST))
        assertFalse(results.drop(16).contains(Advice.FIRST))
    }
    @Test fun `second to first simulator exercises the full estimator and coach`() {
        val estimator = GearEstimator()
        val coach = Coach()
        val results = (0..40).map { t ->
            val r = Simulator.sample(Scenario.SECOND_TO_FIRST, t / 2.0, t * 500L)
            coach.advise(r, estimator.estimate(r, calibration, r.timestampMs), calibration, CoachingConfig(), r.timestampMs)
        }
        assertTrue(results.contains(Advice.FIRST))
    }
    private fun firstSamples(speed: Double = 8.0, rpm: Double = 960.0) =
        samples().map { it.copy(kmh = speed, rpm = rpm) }
    @Test fun `first calibrates at five mph without requiring higher speed or a thousand rpm`() {
        val result = CalibrationCapture.assess(firstSamples(), 5000, Source.LIVE, 1)
        assertTrue(result.ready)
        assertEquals(120.0, result.ratio!!, 0.001)
        assertFalse(CalibrationCapture.assess(firstSamples(), 5000, Source.LIVE, 2).ready)
    }
    @Test fun `stationary creeping engine off and invalid gear cannot calibrate first`() {
        for ((speed, rpm) in listOf(0.0 to 850.0, 5.0 to 850.0, 8.0 to 0.0, 8.0 to 700.0))
            assertNull(CalibrationCapture.capture(firstSamples(speed, rpm), 5000, Source.LIVE, 1))
        for (gear in listOf(0, 7)) assertNull(CalibrationCapture.capture(firstSamples(), 5000, Source.LIVE, gear))
    }
    @Test fun `capture requires five actual seconds of readings and not just a sample count`() {
        val tooShort = firstSamples().dropLast(1)
        val assessment = CalibrationCapture.assess(tooShort, 4500, Source.LIVE, 1)
        assertFalse(assessment.ready)
        assertTrue(assessment.message.contains("4/5 seconds"))
        val slowerPolling = firstSamples().filterIndexed { index, _ -> index % 2 == 0 }
        assertTrue(CalibrationCapture.assess(slowerPolling, 5000, Source.LIVE, 1).ready)
    }
    @Test fun `low speed capture rejects shifting slipping clutch and speed changes`() {
        val baseline = firstSamples()
        val variations = listOf(
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(rpm = 1300.0) else r },
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(clutchDisengaged = true) else r },
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(synchronized = false) else r },
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(kmh = 10.0, rpm = 1200.0) else r },
        )
        variations.forEach { assertFalse(CalibrationCapture.assess(it, 5000, Source.LIVE, 1).ready) }
    }
    @Test fun `capture rejects nonfinite missing mixed source stale future and interrupted samples`() {
        val baseline = firstSamples()
        val variations = listOf(
            baseline.map { it.copy(rpm = Double.NaN) }, baseline.map { it.copy(kmh = Double.POSITIVE_INFINITY) },
            baseline.map { it.copy(rpm = null) }, baseline.map { it.copy(kmh = null) },
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(source = Source.SIMULATION) else r },
            baseline.mapIndexed { i, r -> if (i >= 5) r.copy(timestampMs = r.timestampMs + 2000) else r },
        )
        variations.forEach { assertFalse(CalibrationCapture.assess(it, it.last().timestampMs, Source.LIVE, 1).ready) }
        assertFalse(CalibrationCapture.assess(baseline, 9000, Source.LIVE, 1).ready)
        assertFalse(CalibrationCapture.assess(baseline, 4000, Source.LIVE, 1).ready)
        assertFalse(CalibrationCapture.assess(emptyList(), 0, Source.LIVE, 1).ready)
    }
    @Test fun `old manoeuvres stop blocking capture after a new stable window`() {
        val older = firstSamples().map { it.copy(timestampMs = it.timestampMs - 10000, kmh = 0.0) }
        assertTrue(CalibrationCapture.assess(older + firstSamples(), 5000, Source.LIVE, 1).ready)
    }
}
