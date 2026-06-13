package com.justb81.compassduel.game.gesture

/**
 * A single motion sample produced by the device sensor stack.
 *
 * All angles are in degrees; [linearAccelMagnitude] is the net linear
 * acceleration magnitude in m/s² (|a| minus gravity, as produced by
 * the accelerometer-minus-gravity calculation).
 *
 * @param timestampMillis Epoch millis when the sample was captured.
 * @param pitchDegrees Forward/backward tilt: positive = tilted forward (phone face up ↗).
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
 * Continuous posture (shield/bubble) is queried via [GestureClassifier.isShieldPosture]
 * rather than emitted as an event, since it is a sustained state rather than a one-shot action.
 */
enum class GestureEvent {
    /** Phone tilted forward beyond the pitch threshold with a simultaneous shake spike. */
    ATTACK,

    /**
     * Phone pitch swung ≥25° in the direction opposite to the baseline pitch within
     * 300 ms without a shake spike. Does not require the pitch to cross zero (e.g.
     * +30° → +2° is a valid 28° reversal). Never emitted when dodge is disabled (Kids Mode).
     */
    DODGE,
}

/**
 * Tuning constants for the gesture classifier.
 *
 * All threshold values are centralized here so gesture sensitivity can be
 * adjusted without touching classifier logic.
 */
object GestureThresholds {
    /** Minimum pitch angle (degrees) required for an ATTACK gesture. */
    const val ATTACK_PITCH_DEGREES = 20f

    /** Maximum |pitch| (degrees) for the device to be considered in shield posture. */
    const val SHIELD_PITCH_DEGREES = 15f

    /** Standard-mode shake threshold (m/s²): requires a firm, deliberate shake. */
    const val STANDARD_SHAKE_MPS2 = 2.5f

    /** Minimum pitch swing (degrees) between two samples to classify a DODGE. */
    const val DODGE_TILT_DELTA_DEGREES = 25f

    /** Time window (ms) within which the pitch must reverse to count as a DODGE. */
    const val DODGE_WINDOW_MILLIS = 300L

    /** Minimum time (ms) that must pass between two consecutive ATTACK events. */
    const val SHAKE_DEBOUNCE_MILLIS = 400L
}

/**
 * Pure gesture classifier that converts a stream of [MotionSample] into discrete
 * [GestureEvent]s and a continuous shield-posture query.
 *
 * The classifier is completely sensor-free: it operates on already-computed
 * pitch/roll/accel values, making it straightforward to unit-test with synthetic
 * sample sequences.
 *
 * **ATTACK** fires when:
 * 1. `pitchDegrees > [GestureThresholds.ATTACK_PITCH_DEGREES]`, and
 * 2. `linearAccelMagnitude > [shakeThresholdMps2]`,
 * 3. subject to a [GestureThresholds.SHAKE_DEBOUNCE_MILLIS] debounce window.
 *
 * **DODGE** fires when the pitch swings at least [GestureThresholds.DODGE_TILT_DELTA_DEGREES]
 * in the direction opposite to the previous sample's pitch within
 * [GestureThresholds.DODGE_WINDOW_MILLIS] ms, without a simultaneous shake spike.
 * The reversal is detected by sign: the swing direction must oppose the baseline pitch,
 * so +30 → +2 (28° back toward zero) qualifies without requiring a strict zero-crossing.
 * Never fires when [dodgeEnabled] is false.
 *
 * **Shield posture** is a continuous boolean available via [isShieldPosture]: true when
 * |pitch| ≤ [GestureThresholds.SHIELD_PITCH_DEGREES].
 *
 * @param shakeThresholdMps2 Shake spike magnitude that triggers an ATTACK.
 *   Use [GestureThresholds.STANDARD_SHAKE_MPS2] for Standard Mode,
 *   [com.justb81.compassduel.game.kids.KidsRules.SHAKE_THRESHOLD_MPS2] for Kids Mode.
 * @param dodgeEnabled When false, DODGE events are never emitted (Kids Mode).
 */
class GestureClassifier(
    private val shakeThresholdMps2: Float,
    private val dodgeEnabled: Boolean,
) {
    /** Epoch millis at which the last ATTACK event fired; 0 = never. */
    private var lastAttackMillis: Long = 0L

    /** The most recent sample, retained for the dodge pitch-swing check. */
    private var previousSample: MotionSample? = null

    /**
     * Feeds one sensor sample into the classifier.
     *
     * @return A [GestureEvent] if a gesture was detected, or null for normal motion.
     */
    fun onSample(sample: MotionSample): GestureEvent? {
        val prev = previousSample
        previousSample = sample

        // --- ATTACK detection ---
        val isShakeSpike = sample.linearAccelMagnitude > shakeThresholdMps2
        val isPitchedForward = sample.pitchDegrees > GestureThresholds.ATTACK_PITCH_DEGREES
        val isDebounced = (sample.timestampMillis - lastAttackMillis) >= GestureThresholds.SHAKE_DEBOUNCE_MILLIS

        if (isPitchedForward && isShakeSpike && isDebounced) {
            lastAttackMillis = sample.timestampMillis
            return GestureEvent.ATTACK
        }

        // --- DODGE detection ---
        // A DODGE is a large, rapid pitch reversal — the pitch must swing at least
        // DODGE_TILT_DELTA_DEGREES in the direction opposite to the previous sample's
        // pitch, within DODGE_WINDOW_MILLIS.  We detect direction reversal by checking
        // that the swing direction (sign of delta) is opposite to the baseline pitch
        // direction (sign of prev.pitchDegrees).  This correctly handles near-zero
        // baselines (+30 → +2 is a reversal relative to +30) without requiring the
        // pitch to actually cross zero.
        if (dodgeEnabled && !isShakeSpike && prev != null) {
            val withinWindow = (sample.timestampMillis - prev.timestampMillis) <= GestureThresholds.DODGE_WINDOW_MILLIS
            val swingDelta = sample.pitchDegrees - prev.pitchDegrees
            val isLargeSwing = kotlin.math.abs(swingDelta) >= GestureThresholds.DODGE_TILT_DELTA_DEGREES
            // Reversal: the swing moved opposite to the direction of the baseline pitch.
            // When prev is exactly zero the swing is still a valid reversal if it is large enough.
            val isReversal = prev.pitchDegrees * swingDelta < 0f ||
                (prev.pitchDegrees == 0f && isLargeSwing)

            if (withinWindow && isReversal && isLargeSwing) {
                return GestureEvent.DODGE
            }
        }

        return null
    }

    /**
     * Returns true when the current pitch indicates shield (or magic bubble) posture.
     *
     * This is a pure function of the current pitch value and does not depend on
     * classifier state — call-sites may query it independently of [onSample].
     *
     * @param pitchDegrees The device's current pitch in degrees.
     */
    fun isShieldPosture(pitchDegrees: Float): Boolean =
        kotlin.math.abs(pitchDegrees) <= GestureThresholds.SHIELD_PITCH_DEGREES

    /** Resets all internal classifier state (debounce timer, previous sample). */
    fun reset() {
        lastAttackMillis = 0L
        previousSample = null
    }
}
