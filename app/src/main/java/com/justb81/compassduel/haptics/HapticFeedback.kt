package com.justb81.compassduel.haptics

import android.os.VibrationEffect
import android.os.Vibrator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the system [Vibrator] that provides named, mode-aware
 * haptic feedback methods for the game screen.
 *
 * All methods are safe to call when the device has no vibrator; they are
 * no-ops in that case. Kids Mode callers must use only [kidsCaught] and
 * [kidsStar] — never [hitReceived] or [eliminated].
 */
@Singleton
class HapticFeedback @Inject constructor(
    private val vibrator: Vibrator,
) {

    /** Short click: an attack landed on an opponent. */
    fun hitLanded() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /**
     * Long buzz: the local player was hit.
     *
     * **Standard mode only.** Do not call from Kids Mode paths.
     */
    fun hitReceived() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(HIT_RECEIVED_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Double-click feedback: an attack was blocked by a shield / magic bubble. */
    fun blocked() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }

    /**
     * Rhythmic waveform: a player was eliminated.
     *
     * **Standard mode only.** Do not call from Kids Mode paths.
     */
    fun eliminated() {
        if (!vibrator.hasVibrator()) return
        val timings = longArrayOf(ELIMINATED_TIMINGS_0, ELIMINATED_TIMINGS_1, ELIMINATED_TIMINGS_2, ELIMINATED_TIMINGS_3, ELIMINATED_TIMINGS_4, ELIMINATED_TIMINGS_5)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT))
    }

    /**
     * Light tick: an opponent is aiming at the local player.
     *
     * Rate-limited internally so it fires at most once per [IN_CROSSHAIRS_INTERVAL_MILLIS].
     */
    fun inCrosshairs() {
        val now = System.currentTimeMillis()
        if (now - lastCrosshairsMillis < IN_CROSSHAIRS_INTERVAL_MILLIS) return
        lastCrosshairsMillis = now
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    /**
     * Single gentle tick: a sparkle was caught (Kids Mode).
     *
     * Only use in Kids Mode UI paths.
     */
    fun kidsCaught() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    /**
     * Gentle double tick: a star was awarded (Kids Mode).
     *
     * Only use in Kids Mode UI paths.
     */
    fun kidsStar() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }

    // -------------------------------------------------------------------------
    // Rate-limit state
    // -------------------------------------------------------------------------

    private var lastCrosshairsMillis = 0L

    companion object {
        private const val HIT_RECEIVED_MILLIS = 200L
        private const val IN_CROSSHAIRS_INTERVAL_MILLIS = 1_000L
        private const val NO_REPEAT = -1
        // Elimination waveform timings: off, on, off, on, off, on
        private const val ELIMINATED_TIMINGS_0 = 0L
        private const val ELIMINATED_TIMINGS_1 = 100L
        private const val ELIMINATED_TIMINGS_2 = 80L
        private const val ELIMINATED_TIMINGS_3 = 150L
        private const val ELIMINATED_TIMINGS_4 = 80L
        private const val ELIMINATED_TIMINGS_5 = 200L
    }
}
