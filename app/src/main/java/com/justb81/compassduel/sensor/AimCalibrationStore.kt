package com.justb81.compassduel.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped holder for an [AimCalibration] captured **before** the match starts
 * (typically in the lobby, where all players are gathered and idle).
 *
 * Calibration is a purely local-device concern: each player calibrates their own phone and
 * the captured offset never travels over the Nearby network. This store is the hand-off point
 * between the lobby (which captures the offset) and the game screen (which consumes it when the
 * round begins) without coupling the two ViewModels or touching the on-wire protocol.
 *
 * The lobby capture is the primary opportunity; the game screen keeps a buzzer-time capture as a
 * fallback for the case where the player skipped lobby calibration (see
 * [com.justb81.compassduel.ui.screens.game.GameViewModel]).
 */
@Singleton
class AimCalibrationStore @Inject constructor() {

    private val _calibration = MutableStateFlow<AimCalibration?>(null)

    /** The lobby-captured calibration, or null when the player has not calibrated yet. */
    val calibration: StateFlow<AimCalibration?> = _calibration.asStateFlow()

    /** True once a calibration has been captured (drives the lobby "calibrated" affordance). */
    val isCalibrated: Boolean get() = _calibration.value != null

    /**
     * Records [rawAzimuthDegrees] as the player's forward-facing offset.
     *
     * @param rawAzimuthDegrees Latest raw compass azimuth in `[0, 360)`.
     */
    fun capture(rawAzimuthDegrees: Float) {
        _calibration.value = AimCalibration(rawAzimuthDegrees)
    }

    /** Clears any stored calibration (e.g. when leaving the session). */
    fun clear() {
        _calibration.value = null
    }
}
