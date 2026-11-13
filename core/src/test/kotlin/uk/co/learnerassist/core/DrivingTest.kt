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
        assertEquals(53.0, CalibrationCapture.capture(samples(), 5000, Source.LIVE)!!, 0.001)
    }
    @Test fun `calibration rejects insufficient unstable stale mixed or clutch samples`() {
        assertNull(CalibrationCapture.capture(samples().take(4), 5000, Source.LIVE))
        assertNull(CalibrationCapture.capture(samples(), 9000, Source.LIVE))
        assertNull(CalibrationCapture.capture(samples().map { it.copy(kmh = 5.0) }, 5000, Source.LIVE))
        assertNull(CalibrationCapture.capture(samples().mapIndexed { i, r -> if (i == 4) r.copy(rpm = 4000.0) else r }, 5000, Source.LIVE))
        assertNull(CalibrationCapture.capture(samples().mapIndexed { i, r -> if (i == 4) r.copy(source = Source.SIMULATION) else r }, 5000, Source.LIVE))
        assertNull(CalibrationCapture.capture(samples().map { it.copy(clutchDisengaged = true) }, 5000, Source.LIVE))
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
}
