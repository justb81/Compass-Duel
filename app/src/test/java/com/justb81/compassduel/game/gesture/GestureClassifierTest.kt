package com.justb81.compassduel.game.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GestureClassifierTest {

    private val standardClassifier = GestureClassifier(
        shakeThresholdMps2 = GestureThresholds.STANDARD_SHAKE_MPS2,
        dodgeEnabled = true,
    )

    private val kidsClassifier = GestureClassifier(
        shakeThresholdMps2 = 1.5f, // KidsRules.SHAKE_THRESHOLD_MPS2
        dodgeEnabled = false,
    )

    private val baseTime = 1_000_000L

    @BeforeEach
    fun setUp() {
        standardClassifier.reset()
        kidsClassifier.reset()
    }

    // ---------------------------------------------------------------------------
    // ATTACK — requires both pitch threshold AND shake spike
    // ---------------------------------------------------------------------------

    @Test
    fun `ATTACK fires when pitch is above threshold and shake is strong enough`() {
        val sample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 1f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 0.1f,
        )
        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(sample))
    }

    @Test
    fun `ATTACK does not fire when pitch is above threshold but shake is too weak`() {
        val sample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 1f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 - 0.1f,
        )
        assertNull(standardClassifier.onSample(sample))
    }

    @Test
    fun `ATTACK does not fire when shake is strong but pitch is below threshold`() {
        val sample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES - 1f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 0.1f,
        )
        assertNull(standardClassifier.onSample(sample))
    }

    // ---------------------------------------------------------------------------
    // ATTACK — debounce suppresses rapid double-fire
    // ---------------------------------------------------------------------------

    @Test
    fun `debounce suppresses second ATTACK within the debounce window`() {
        val firstSample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 5f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 1f,
        )
        val secondSample = firstSample.copy(
            timestampMillis = baseTime + GestureThresholds.SHAKE_DEBOUNCE_MILLIS - 1,
        )

        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(firstSample))
        assertNull(standardClassifier.onSample(secondSample))
    }

    @Test
    fun `ATTACK fires again after the debounce window has elapsed`() {
        val firstSample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 5f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 1f,
        )
        val secondSample = firstSample.copy(
            timestampMillis = baseTime + GestureThresholds.SHAKE_DEBOUNCE_MILLIS,
        )

        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(firstSample))
        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(secondSample))
    }

    // ---------------------------------------------------------------------------
    // ATTACK — kids vs standard shake threshold
    // ---------------------------------------------------------------------------

    @Test
    fun `kids threshold (1,5) accepts soft wiggle that standard (2,5) ignores`() {
        val softShake = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 5f,
            rollDegrees = 0f,
            linearAccelMagnitude = 2.0f, // above kids 1.5, below standard 2.5
        )

        assertNull(standardClassifier.onSample(softShake))
        assertEquals(GestureEvent.ATTACK, kidsClassifier.onSample(softShake))
    }

    // ---------------------------------------------------------------------------
    // DODGE — disabled in Kids Mode
    // ---------------------------------------------------------------------------

    @Test
    fun `DODGE is never emitted when dodge is disabled (kids mode)`() {
        val first = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = 20f,
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )
        val second = MotionSample(
            timestampMillis = baseTime + 100L,
            pitchDegrees = -20f, // reversed pitch
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )

        kidsClassifier.onSample(first)
        assertNull(kidsClassifier.onSample(second))
    }

    @Test
    fun `DODGE fires in standard mode when pitch reverses by at least 25 degrees within 300 ms`() {
        val first = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = 20f,
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )
        val second = MotionSample(
            timestampMillis = baseTime + 100L,
            pitchDegrees = -10f, // reversed: swing = 30° which exceeds 25°
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )

        standardClassifier.onSample(first)
        assertEquals(GestureEvent.DODGE, standardClassifier.onSample(second))
    }

    @Test
    fun `DODGE does not fire when pitch swing is below 25 degrees`() {
        val first = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = 10f,
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )
        val second = MotionSample(
            timestampMillis = baseTime + 100L,
            pitchDegrees = -10f, // reversed but swing = 20° (below 25°)
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )

        standardClassifier.onSample(first)
        assertNull(standardClassifier.onSample(second))
    }

    @Test
    fun `DODGE does not fire when the pitch reversal is outside the 300 ms window`() {
        val first = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = 20f,
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )
        val second = MotionSample(
            timestampMillis = baseTime + GestureThresholds.DODGE_WINDOW_MILLIS + 1,
            pitchDegrees = -20f, // reversed, but too late
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )

        standardClassifier.onSample(first)
        assertNull(standardClassifier.onSample(second))
    }

    @Test
    fun `DODGE does not fire when there is a simultaneous shake spike`() {
        val first = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = 20f,
            rollDegrees = 0f,
            linearAccelMagnitude = 0.1f,
        )
        val second = MotionSample(
            timestampMillis = baseTime + 100L,
            pitchDegrees = -10f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 1f, // shake spike
        )

        standardClassifier.onSample(first)
        // Shake spike prevents DODGE from firing (could be ATTACK if pitch conditions matched)
        assertNull(standardClassifier.onSample(second))
    }

    // ---------------------------------------------------------------------------
    // Shield posture — flat phone
    // ---------------------------------------------------------------------------

    @Test
    fun `flat phone posture is classified as shielding`() {
        assertTrue(standardClassifier.isShieldPosture(pitchDegrees = 0f))
        assertTrue(standardClassifier.isShieldPosture(pitchDegrees = GestureThresholds.SHIELD_PITCH_DEGREES))
        assertTrue(standardClassifier.isShieldPosture(pitchDegrees = -GestureThresholds.SHIELD_PITCH_DEGREES))
    }

    @Test
    fun `tilted phone is not classified as shielding`() {
        assertFalse(standardClassifier.isShieldPosture(pitchDegrees = GestureThresholds.SHIELD_PITCH_DEGREES + 1f))
        assertFalse(standardClassifier.isShieldPosture(pitchDegrees = -(GestureThresholds.SHIELD_PITCH_DEGREES + 1f)))
    }

    // ---------------------------------------------------------------------------
    // reset() clears state
    // ---------------------------------------------------------------------------

    @Test
    fun `reset clears the debounce timer so ATTACK can fire immediately`() {
        val attackSample = MotionSample(
            timestampMillis = baseTime,
            pitchDegrees = GestureThresholds.ATTACK_PITCH_DEGREES + 5f,
            rollDegrees = 0f,
            linearAccelMagnitude = GestureThresholds.STANDARD_SHAKE_MPS2 + 1f,
        )
        val soonAfter = attackSample.copy(
            timestampMillis = baseTime + 50L, // within debounce window
        )

        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(attackSample))
        // Without reset, this would be suppressed
        standardClassifier.reset()
        assertEquals(GestureEvent.ATTACK, standardClassifier.onSample(soonAfter))
    }
}
