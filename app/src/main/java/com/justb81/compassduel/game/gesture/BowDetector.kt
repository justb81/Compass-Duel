package com.justb81.compassduel.game.gesture

import kotlin.math.abs

/**
 * A single orientation sample fed into the [BowDetector].
 *
 * @param timestampMillis Epoch millis when the sample was captured.
 * @param pitchDegrees Forward tilt: positive = top of phone away from player
 *   (aiming forward/down). Same convention as [MotionSample.pitchDegrees] and
 *   [com.justb81.compassduel.sensor.OrientationSample.pitchDegrees].
 * @param azimuthDegrees Raw magnetic-north azimuth in `[0, 360)`.
 */
data class BowSample(
    val timestampMillis: Long,
    val pitchDegrees: Float,
    val azimuthDegrees: Float,
)

/**
 * Tuning constants for the [BowDetector].
 *
 * A "bow" is a deliberate forward tilt of the phone while aimed at a target:
 * the pitch rises from the aiming posture past [BOW_ONSET_PITCH_DEGREES], reaches
 * at least [BOW_DEEP_PITCH_DEGREES], then returns below [BOW_RETURN_PITCH_DEGREES].
 */
object BowThresholds {
    /** Pitch (degrees) at which a forward tilt is recognised as the start of a bow. */
    const val BOW_ONSET_PITCH_DEGREES = 30f

    /** Pitch (degrees) the tilt must reach for the bow to count as "deep enough". */
    const val BOW_DEEP_PITCH_DEGREES = 55f

    /** Pitch (degrees) the phone must return below to complete the bow. */
    const val BOW_RETURN_PITCH_DEGREES = 25f

    /** Minimum bow duration (ms) — rejects an instantaneous spike as a bow. */
    const val BOW_MIN_DURATION_MILLIS = 150L

    /** Maximum bow duration (ms) — a tilt held longer than this aborts the gesture. */
    const val BOW_MAX_DURATION_MILLIS = 2_000L
}

/**
 * Internal bow recognition phase.
 */
private enum class BowPhase { IDLE, DESCENDING, ASCENDING }

/**
 * Pure, sensor-free classifier that recognises a "bow to greet" gesture and reports
 * the azimuth the phone was pointing at when the bow began.
 *
 * The greeting handshake captures the bearing between two players: the player aims at
 * the opponent and bows (tilts the phone forward and back). The **azimuth is sampled at
 * bow onset** — while the phone is still aimed at the target — because at the bottom of
 * the tilt the phone faces the floor and its azimuth is unstable.
 *
 * Mirrors [GestureClassifier]: it operates on already-computed pitch/azimuth values and
 * is trivially unit-testable with synthetic sample sequences. Call [reset] to reuse the
 * detector for greeting multiple opponents in sequence.
 */
class BowDetector {

    private var phase: BowPhase = BowPhase.IDLE

    /** Most recent azimuth observed while in the aiming posture (frozen at onset). */
    private var aimedAzimuth: Float = 0f

    /** Captured azimuth at the onset of the current bow. */
    private var capturedAzimuth: Float = 0f

    /** Epoch millis at which the current bow began; 0 when not bowing. */
    private var onsetMillis: Long = 0L

    /**
     * Feeds one orientation sample into the detector.
     *
     * @return the azimuth captured at bow onset when a complete bow is recognised on
     *   this sample, or null otherwise.
     */
    fun onSample(sample: BowSample): Float? = when (phase) {
        BowPhase.IDLE -> onIdle(sample)
        BowPhase.DESCENDING -> onDescending(sample)
        BowPhase.ASCENDING -> onAscending(sample)
    }

    private fun onIdle(sample: BowSample): Float? {
        if (sample.pitchDegrees >= BowThresholds.BOW_ONSET_PITCH_DEGREES) {
            capturedAzimuth = aimedAzimuth
            onsetMillis = sample.timestampMillis
            phase = BowPhase.DESCENDING
        } else {
            // Still aimed (upright) — remember the heading so onset can freeze it.
            aimedAzimuth = sample.azimuthDegrees
        }
        return null
    }

    private fun onDescending(sample: BowSample): Float? {
        if (hasTimedOut(sample.timestampMillis)) {
            reset()
            return null
        }
        if (sample.pitchDegrees >= BowThresholds.BOW_DEEP_PITCH_DEGREES) {
            phase = BowPhase.ASCENDING
        }
        return null
    }

    private fun onAscending(sample: BowSample): Float? {
        if (hasTimedOut(sample.timestampMillis)) {
            reset()
            return null
        }
        val longEnough = sample.timestampMillis - onsetMillis >= BowThresholds.BOW_MIN_DURATION_MILLIS
        if (sample.pitchDegrees <= BowThresholds.BOW_RETURN_PITCH_DEGREES && longEnough) {
            val result = capturedAzimuth
            reset()
            return result
        }
        return null
    }

    private fun hasTimedOut(nowMillis: Long): Boolean =
        nowMillis - onsetMillis > BowThresholds.BOW_MAX_DURATION_MILLIS

    /** Resets the detector to its idle state so it can capture a fresh bow. */
    fun reset() {
        phase = BowPhase.IDLE
        onsetMillis = 0L
    }

    companion object {
        /** Convenience: true when [pitchDegrees] is within the aiming (upright) posture. */
        fun isAiming(pitchDegrees: Float): Boolean =
            abs(pitchDegrees) < BowThresholds.BOW_ONSET_PITCH_DEGREES
    }
}
