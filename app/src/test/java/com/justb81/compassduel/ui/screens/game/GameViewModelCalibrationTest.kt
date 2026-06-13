package com.justb81.compassduel.ui.screens.game

import com.justb81.compassduel.sensor.AimCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GameViewModel.resolveCalibration] — the pure helper that decides
 * which [AimCalibration] to use at the start of the PLAYING phase, or whether to
 * defer the capture until the first real orientation sample arrives (#69).
 *
 * These tests exercise only the companion-object function and require no Android
 * framework, coroutines, or Hilt wiring.
 */
class GameViewModelCalibrationTest {

    // -------------------------------------------------------------------------
    // UseNow — lobby calibration takes priority
    // -------------------------------------------------------------------------

    @Test
    fun `stored lobby calibration is used immediately when present, regardless of rawAzimuth`() {
        val lobby = AimCalibration(facingOffsetDegrees = 123f)
        val decision = GameViewModel.resolveCalibration(storedCal = lobby, rawAzimuth = 45f)
        val useNow = decision as GameViewModel.CalibrationDecision.UseNow
        assertEquals(lobby, useNow.calibration)
    }

    @Test
    fun `stored lobby calibration is used when rawAzimuth is null`() {
        val lobby = AimCalibration(facingOffsetDegrees = 270f)
        val decision = GameViewModel.resolveCalibration(storedCal = lobby, rawAzimuth = null)
        val useNow = decision as GameViewModel.CalibrationDecision.UseNow
        assertEquals(lobby, useNow.calibration)
    }

    // -------------------------------------------------------------------------
    // UseNow — buzzer-time fallback from first real sensor reading
    // -------------------------------------------------------------------------

    @Test
    fun `no lobby calibration but real azimuth available creates fallback calibration`() {
        val decision = GameViewModel.resolveCalibration(storedCal = null, rawAzimuth = 90f)
        val useNow = decision as GameViewModel.CalibrationDecision.UseNow
        assertEquals(AimCalibration(facingOffsetDegrees = 90f), useNow.calibration)
    }

    @Test
    fun `buzzer fallback uses the exact rawAzimuth as the facing offset`() {
        val decision = GameViewModel.resolveCalibration(storedCal = null, rawAzimuth = 350f)
        val useNow = decision as GameViewModel.CalibrationDecision.UseNow
        // calibrate(350f) with offset 350f should yield 0°
        assertEquals(0f, useNow.calibration.calibrate(350f), DELTA)
    }

    // -------------------------------------------------------------------------
    // Defer — no lobby calibration and no sensor reading yet
    // -------------------------------------------------------------------------

    @Test
    fun `returns Defer when no lobby calibration and rawAzimuth is null`() {
        val decision = GameViewModel.resolveCalibration(storedCal = null, rawAzimuth = null)
        assertEquals(GameViewModel.CalibrationDecision.Defer, decision)
    }

    @Test
    fun `Defer is returned only when both inputs are null`() {
        // Sanity check: providing any non-null input must not return Defer
        val withRaw = GameViewModel.resolveCalibration(storedCal = null, rawAzimuth = 0f)
        assertInstanceOf(GameViewModel.CalibrationDecision.UseNow::class.java, withRaw)

        val withStored = GameViewModel.resolveCalibration(
            storedCal = AimCalibration(0f),
            rawAzimuth = null,
        )
        assertInstanceOf(GameViewModel.CalibrationDecision.UseNow::class.java, withStored)
    }

    companion object {
        private const val DELTA = 0.001f
    }
}
