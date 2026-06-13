package com.justb81.compassduel.sensor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimCalibrationTest {

    @Test
    fun `calibrate subtracts offset and normalises into 0-360`() {
        val calibration = AimCalibration(facingOffsetDegrees = 90f)
        assertEquals(0f, calibration.calibrate(90f), DELTA)
        assertEquals(90f, calibration.calibrate(180f), DELTA)
    }

    @Test
    fun `calibrate wraps around correctly when raw is less than offset`() {
        // raw 10°, offset 350° → (10 − 350 + 360) % 360 = 20°
        val calibration = AimCalibration(facingOffsetDegrees = 350f)
        assertEquals(20f, calibration.calibrate(10f), DELTA)
    }

    @Test
    fun `calibrate with zero offset returns raw azimuth unchanged`() {
        val calibration = AimCalibration(facingOffsetDegrees = 0f)
        assertEquals(270f, calibration.calibrate(270f), DELTA)
    }

    @Test
    fun `calibrate result is always in 0 inclusive to 360 exclusive`() {
        val calibration = AimCalibration(facingOffsetDegrees = 180f)
        val result = calibration.calibrate(180f)
        assertTrue(result >= 0f && result < 360f) { "Expected result in [0, 360) but was $result" }
    }

    @Test
    fun `calibrate with full-circle offset returns zero for same raw angle`() {
        val calibration = AimCalibration(facingOffsetDegrees = 270f)
        assertEquals(90f, calibration.calibrate(360f), DELTA)
    }

    companion object {
        private const val DELTA = 0.001f
    }
}
