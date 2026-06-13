package com.justb81.compassduel.game.gesture

/**
 * A single motion sample produced by the device sensor stack.
 *
 * All angles are in degrees; [linearAccelMagnitude] is the net linear
 * acceleration magnitude in m/s² (|a| minus gravity, as produced by
 * the accelerometer-minus-gravity calculation).
 *
 * @param timestampMillis Epoch millis when the sample was captured.
 * @param pitchDegrees Forward/backward tilt: 0 ≈ upright (screen toward the player).
 * @param rollDegrees Sideways tilt in degrees.
 * @param linearAccelMagnitude Net linear acceleration in m/s² (gravity removed).
 */
data class MotionSample(
    val timestampMillis: Long,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val linearAccelMagnitude: Float,
)

/**
 * Discrete gesture events that the classifier emits.
 *
 * Continuous shield posture is queried via [GestureClassifier.isShieldActive]
 * rather than emitted as an event, since it is a sustained state rather than a
 * one-shot action.
 */
enum class GestureEvent {
    /** A quick motion swing/jerk toward an opponent — fires in the current aim direction. */
    FIRE,
}

/**
 * Tuning constants for the gesture classifier.
 *
 * All threshold values are centralized here so gesture sensitivity can be
 * adjusted without touching classifier logic.
 */
object GestureThresholds {
    /** Maximum |pitch| (degrees) for the device to count as the upright shield posture. */
    const val SHIELD_UPRIGHT_PITCH_DEGREES = 25f

    /** Maximum linear acceleration (m/s²) for the device to count as "held steady". */
    const val STEADY_ACCEL_MPS2 = 1.2f

    /** Continuous time (ms) the device must be held upright and steady to activate the shield. */
    const val SHIELD_HOLD_MILLIS = 1_000L

    /** Standard-mode swing threshold (m/s²): a deliberate jerk that fires. */
    const val FIRE_SWING_MPS2 = 2.5f

    /** Minimum time (ms) between two consecutive FIRE events. */
    const val FIRE_DEBOUNCE_MILLIS = 500L
}

/**
 * Pure gesture classifier that converts a stream of [MotionSample] into a discrete
 * [GestureEvent.FIRE] and a continuous shield state.
 *
 * The classifier is completely sensor-free: it operates on already-computed
 * pitch/roll/accel values, making it straightforward to unit-test with synthetic
 * sample sequences.
 *
 * **Shield** activates after the device is held **upright (|pitch| ≤
 * [GestureThresholds.SHIELD_UPRIGHT_PITCH_DEGREES]) and steady
 * (linearAccel ≤ [GestureThresholds.STEADY_ACCEL_MPS2])** continuously for
 * [GestureThresholds.SHIELD_HOLD_MILLIS] ms. Once active it stays active while the
 * device remains upright; it drops on a FIRE swing or when the device leaves the
 * upright band. Steadiness is required only to *arm* the timer, not to maintain an
 * already-active shield. Shield is never armed when [shieldEnabled] is false (Kids Mode).
 *
 * **FIRE** is emitted on a quick swing/jerk —
 * `linearAccelMagnitude ≥ [fireSwingThresholdMps2]` — subject to a
 * [GestureThresholds.FIRE_DEBOUNCE_MILLIS] debounce. A FIRE also drops the shield.
 *
 * @param fireSwingThresholdMps2 Swing magnitude that triggers a FIRE.
 *   Use [GestureThresholds.FIRE_SWING_MPS2] for Standard Mode,
 *   [com.justb81.compassduel.game.kids.KidsRules.SHAKE_THRESHOLD_MPS2] for Kids Mode.
 * @param shieldEnabled When false, the shield never arms (Kids Mode).
 */
class GestureClassifier(
    private val fireSwingThresholdMps2: Float,
    private val shieldEnabled: Boolean,
) {
    /** Epoch millis at which the last FIRE event fired; 0 = never. */
    private var lastFireMillis: Long = 0L

    /** True while the shield is currently active. */
    private var shieldActive: Boolean = false

    /** Epoch millis at which the current continuous upright-and-steady hold began; null = not arming. */
    private var uprightSteadySinceMillis: Long? = null

    /** True while the shield is currently active (host-independent, local view). */
    val isShieldActive: Boolean get() = shieldActive

    /**
     * Feeds one sensor sample into the classifier.
     *
     * @return [GestureEvent.FIRE] when a swing fired, or null otherwise.
     */
    fun onSample(sample: MotionSample): GestureEvent? {
        if (isSwing(sample) && isFireDebounced(sample.timestampMillis)) {
            lastFireMillis = sample.timestampMillis
            shieldActive = false
            uprightSteadySinceMillis = null
            return GestureEvent.FIRE
        }

        if (shieldEnabled) {
            updateShield(sample)
        }
        return null
    }

    private fun updateShield(sample: MotionSample) {
        if (!isUpright(sample)) {
            uprightSteadySinceMillis = null
            shieldActive = false
            return
        }
        if (shieldActive) return
        if (isSteady(sample)) {
            val since = uprightSteadySinceMillis ?: sample.timestampMillis.also { uprightSteadySinceMillis = it }
            if (sample.timestampMillis - since >= GestureThresholds.SHIELD_HOLD_MILLIS) {
                shieldActive = true
            }
        } else {
            // Motion broke the calm — the 1 s hold must accumulate again from scratch.
            uprightSteadySinceMillis = null
        }
    }

    /**
     * Progress of the shield "loading" animation in `[0, 1]`.
     *
     * Returns 1 when the shield is active, the fraction of
     * [GestureThresholds.SHIELD_HOLD_MILLIS] elapsed while arming, or 0 otherwise.
     * This is a local-only signal for the UI; it never affects [onSample] output.
     *
     * @param nowMillis The current time used to measure arming progress.
     */
    fun shieldArmProgress(nowMillis: Long): Float {
        if (shieldActive) return 1f
        val since = uprightSteadySinceMillis ?: return 0f
        return ((nowMillis - since).toFloat() / GestureThresholds.SHIELD_HOLD_MILLIS).coerceIn(0f, 1f)
    }

    private fun isSwing(sample: MotionSample): Boolean =
        sample.linearAccelMagnitude >= fireSwingThresholdMps2

    private fun isFireDebounced(nowMillis: Long): Boolean =
        (nowMillis - lastFireMillis) >= GestureThresholds.FIRE_DEBOUNCE_MILLIS

    private fun isUpright(sample: MotionSample): Boolean =
        kotlin.math.abs(sample.pitchDegrees) <= GestureThresholds.SHIELD_UPRIGHT_PITCH_DEGREES

    private fun isSteady(sample: MotionSample): Boolean =
        sample.linearAccelMagnitude <= GestureThresholds.STEADY_ACCEL_MPS2

    /** Resets all internal classifier state (debounce timer, shield, arming timer). */
    fun reset() {
        lastFireMillis = 0L
        shieldActive = false
        uprightSteadySinceMillis = null
    }
}
