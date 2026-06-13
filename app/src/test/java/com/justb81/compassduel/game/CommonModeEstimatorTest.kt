package com.justb81.compassduel.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommonModeEstimatorTest {

    private val delta = 0.01f

    @Test
    fun `shared rotation of all players is recovered as theta`() {
        val baseline = mapOf(1 to 0f, 2 to 90f, 3 to 180f)
        val current = mapOf(1 to 30f, 2 to 120f, 3 to 210f) // everyone drifted +30°
        assertEquals(30f, CommonModeEstimator.estimate(baseline, current), delta)
    }

    @Test
    fun `a single active aimer is rejected by the median`() {
        val baseline = mapOf(1 to 0f, 2 to 90f, 3 to 180f)
        // Players 1 & 2 drifted +30 (vehicle); player 3 slewed +120 while aiming.
        val current = mapOf(1 to 30f, 2 to 120f, 3 to 300f)
        assertEquals(30f, CommonModeEstimator.estimate(baseline, current), delta)
    }

    @Test
    fun `no correction below the minimum player count`() {
        val baseline = mapOf(1 to 0f, 2 to 0f)
        val current = mapOf(1 to 30f, 2 to 30f)
        assertEquals(0f, CommonModeEstimator.estimate(baseline, current), delta)
    }

    @Test
    fun `drift estimate is wrap-around safe near 360`() {
        val baseline = mapOf(1 to 350f, 2 to 350f, 3 to 350f)
        val current = mapOf(1 to 10f, 2 to 10f, 3 to 10f) // +20° across the 360 boundary
        assertEquals(20f, CommonModeEstimator.estimate(baseline, current), delta)
    }
}
