package com.justb81.compassduel.game.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GestureClassifierTest {

    private val standardClassifier = GestureClassifier(
        fireSwingThresholdMps2 = GestureThresholds.FIRE_SWING_MPS2,
        shieldEnabled = true,
    )

    private val kidsClassifier = GestureClassifier(
        fireSwingThresholdMps2 = 1.5f, // KidsRules.SHAKE_THRESHOLD_MPS2
        shieldEnabled = false,
    )

    private val baseTime = 1_000_000L

    @BeforeEach
    fun setUp() {
        standardClassifier.reset()
        kidsClassifier.reset()
    }

    /** Upright + steady sample (arms the shield) unless overridden. */
    private fun sample(
        t: Long,
        pitch: Float = 0f,
        accel: Float = 0f,
    ) = MotionSample(timestampMillis = t, pitchDegrees = pitch, rollDegrees = 0f, linearAccelMagnitude = accel)

    // ---------------------------------------------------------------------------
    // FIRE — a quick swing/jerk
    // ---------------------------------------------------------------------------

    @Test
    fun `FIRE fires on a swing above the threshold`() {
        val swing = sample(baseTime, accel = GestureThresholds.FIRE_SWING_MPS2 + 0.1f)
        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(swing))
    }

    @Test
    fun `FIRE does not fire below the swing threshold`() {
        val gentle = sample(baseTime, accel = GestureThresholds.FIRE_SWING_MPS2 - 0.1f)
        assertNull(standardClassifier.onSample(gentle))
    }

    @Test
    fun `FIRE is debounced within the window and fires again after it elapses`() {
        val first = sample(baseTime, accel = GestureThresholds.FIRE_SWING_MPS2 + 1f)
        val withinWindow = first.copy(timestampMillis = baseTime + GestureThresholds.FIRE_DEBOUNCE_MILLIS - 1)
        val afterWindow = first.copy(timestampMillis = baseTime + GestureThresholds.FIRE_DEBOUNCE_MILLIS)

        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(first))
        assertNull(standardClassifier.onSample(withinWindow))
        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(afterWindow))
    }

    @Test
    fun `kids threshold accepts a softer swing that standard ignores`() {
        val softSwing = sample(baseTime, accel = 2.0f) // above kids 1.5, below standard 2.5
        assertNull(standardClassifier.onSample(softSwing))
        assertEquals(GestureEvent.FIRE, kidsClassifier.onSample(softSwing))
    }

    // ---------------------------------------------------------------------------
    // SHIELD — hold upright & steady for >= 1 s
    // ---------------------------------------------------------------------------

    @Test
    fun `shield activates only after holding upright and steady for the hold duration`() {
        standardClassifier.onSample(sample(baseTime))
        assertFalse(standardClassifier.isShieldActive)
        // Just short of the hold window — still not active.
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS - 1))
        assertFalse(standardClassifier.isShieldActive)
        // At/after the hold window — active.
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS))
        assertTrue(standardClassifier.isShieldActive)
    }

    @Test
    fun `shield does not arm when the phone is not upright`() {
        val tilted = GestureThresholds.SHIELD_UPRIGHT_PITCH_DEGREES + 5f
        standardClassifier.onSample(sample(baseTime, pitch = tilted))
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS + 1, pitch = tilted))
        assertFalse(standardClassifier.isShieldActive)
    }

    @Test
    fun `motion above the steady threshold resets the arming timer`() {
        standardClassifier.onSample(sample(baseTime))
        // Mid-window wobble (above steady, below swing) restarts the hold accumulation.
        standardClassifier.onSample(
            sample(baseTime + WOBBLE_OFFSET_MILLIS, accel = GestureThresholds.STEADY_ACCEL_MPS2 + 0.5f),
        )
        // 1 s after the original start, but only 500 ms of fresh calm → still not active.
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS))
        assertFalse(standardClassifier.isShieldActive)
    }

    @Test
    fun `a fire swing clears an active shield`() {
        activateShield()
        assertTrue(standardClassifier.isShieldActive)
        val swing = sample(baseTime + POST_ACTIVATION_MILLIS, accel = GestureThresholds.FIRE_SWING_MPS2 + 1f)
        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(swing))
        assertFalse(standardClassifier.isShieldActive)
    }

    @Test
    fun `leaving the upright posture clears an active shield`() {
        activateShield()
        assertTrue(standardClassifier.isShieldActive)
        standardClassifier.onSample(
            sample(baseTime + POST_ACTIVATION_MILLIS, pitch = GestureThresholds.SHIELD_UPRIGHT_PITCH_DEGREES + 10f),
        )
        assertFalse(standardClassifier.isShieldActive)
    }

    @Test
    fun `kids mode never activates the shield`() {
        kidsClassifier.onSample(sample(baseTime))
        kidsClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS + 1))
        assertFalse(kidsClassifier.isShieldActive)
    }

    // ---------------------------------------------------------------------------
    // Shield arming progress
    // ---------------------------------------------------------------------------

    @Test
    fun `shieldArmProgress is zero before any upright-steady hold`() {
        assertEquals(0f, standardClassifier.shieldArmProgress(baseTime), DELTA)
    }

    @Test
    fun `shieldArmProgress ramps while arming and is full once active`() {
        standardClassifier.onSample(sample(baseTime))
        assertEquals(
            HALF,
            standardClassifier.shieldArmProgress(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS / 2),
            DELTA,
        )
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS))
        assertEquals(1f, standardClassifier.shieldArmProgress(baseTime + POST_ACTIVATION_MILLIS), DELTA)
    }

    // ---------------------------------------------------------------------------
    // reset()
    // ---------------------------------------------------------------------------

    @Test
    fun `reset clears the shield and the fire debounce`() {
        activateShield()
        val first = sample(baseTime + POST_ACTIVATION_MILLIS, accel = GestureThresholds.FIRE_SWING_MPS2 + 1f)
        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(first))

        standardClassifier.reset()
        assertFalse(standardClassifier.isShieldActive)
        // Debounce cleared → a swing soon after still fires.
        val soonAfter = sample(baseTime + SECOND_SWING_MILLIS, accel = GestureThresholds.FIRE_SWING_MPS2 + 1f)
        assertEquals(GestureEvent.FIRE, standardClassifier.onSample(soonAfter))
    }

    /** Drives the standard classifier through a full upright-steady hold so the shield is active. */
    private fun activateShield() {
        standardClassifier.onSample(sample(baseTime))
        standardClassifier.onSample(sample(baseTime + GestureThresholds.SHIELD_HOLD_MILLIS))
    }

    private companion object {
        const val DELTA = 1e-4f
        const val HALF = 0.5f

        /** A mid-hold wobble offset (< the hold window) used to reset the arming timer. */
        const val WOBBLE_OFFSET_MILLIS = 500L

        /** A time well after the shield has activated. */
        const val POST_ACTIVATION_MILLIS = 5_000L

        /** A second distinct fire time after [POST_ACTIVATION_MILLIS]. */
        const val SECOND_SWING_MILLIS = 5_050L
    }
}
