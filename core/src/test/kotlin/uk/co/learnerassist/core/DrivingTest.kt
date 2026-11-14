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
            baseline.mapIndexed { i, r -> if (i == 5) r.copy(kmh = 14.0, rpm = 1680.0) else r },
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
    @Test fun `first gear captures gentle progress from five to seven mph`() {
        val moving = samples().mapIndexed { i, r ->
            val trueKmh = 8.0 + i * 0.3
            r.copy(kmh = kotlin.math.round(trueKmh), rpm = trueKmh * 120)
        }
        val assessment = CalibrationCapture.assess(moving, 5000, Source.LIVE, 1)
        assertTrue(assessment.message, assessment.ready)
        assertEquals(120.0, assessment.ratio!!, 3.0)
    }
    @Test fun `one kmh reporting steps do not reject consistent first gear ratios`() {
        val quantised = samples().mapIndexed { i, r ->
            val trueKmh = if (i % 2 == 0) 8.49 else 8.51
            r.copy(kmh = kotlin.math.round(trueKmh), rpm = trueKmh * 120)
        }
        val assessment = CalibrationCapture.assess(quantised, 5000, Source.LIVE, 1)
        assertTrue(assessment.message, assessment.ready)
        assertEquals(120.0, assessment.ratio!!, 1.0)
    }
    @Test fun `broader first speed window still rejects slipping ratios`() {
        val slipping = samples().mapIndexed { i, r ->
            val kmh = 8.0 + (i / 4)
            r.copy(kmh = kmh, rpm = kmh * if (i < 5) 120 else 170)
        }
        assertFalse(CalibrationCapture.assess(slipping, 5000, Source.LIVE, 1).ready)
    }
    @Test fun `other gears retain the existing speed and ratio stability limits`() {
        val varying = samples().mapIndexed { i, r ->
            val kmh = 15.0 + i * 0.4
            r.copy(kmh = kmh, rpm = kmh * 78)
        }
        assertFalse(CalibrationCapture.assess(varying, 5000, Source.LIVE, 2).ready)
        val unstable = samples().mapIndexed { i, r -> if (i == 5) r.copy(rpm = r.rpm!! * 1.05) else r }
        assertFalse(CalibrationCapture.assess(unstable, 5000, Source.LIVE, 3).ready)
    }
}
