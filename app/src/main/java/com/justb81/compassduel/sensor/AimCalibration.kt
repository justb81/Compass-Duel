package com.justb81.compassduel.sensor

/**
 * Stores the facing offset captured at round start and converts raw compass azimuth
 * values into calibrated aim directions relative to the player's "forward" direction.
 *
 * ### Calibration procedure
 * At the start of each round all players point their phones toward the center of the
 * play area. The raw azimuth at that moment is recorded as [facingOffsetDegrees].
 * Subsequent raw azimuths are normalized by subtracting the offset so that
 * "straight ahead" always maps to 0°, regardless of room orientation.
 *
 * @param facingOffsetDegrees The raw azimuth recorded when the player faces forward.
 */
data class AimCalibration(val facingOffsetDegrees: Float) {

    /**
     * Converts [rawAzimuthDegrees] into a calibrated aim direction in `[0, 360)`.
     *
     * The subtraction is wrap-around-safe: e.g. raw 10° with offset 350° correctly
     * yields 20° rather than −340°.
     *
     * @param rawAzimuthDegrees Raw compass azimuth from the sensor in `[0, 360)`.
     * @return Calibrated aim azimuth in `[0, 360)`.
     */
    fun calibrate(rawAzimuthDegrees: Float): Float =
        ((rawAzimuthDegrees - facingOffsetDegrees) + FULL_CIRCLE) % FULL_CIRCLE

    companion object {
        private const val FULL_CIRCLE = 360f
    }
}
