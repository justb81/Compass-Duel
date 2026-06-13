package com.justb81.compassduel.game

import kotlin.math.abs

/**
 * Geometry helpers for the host-side hit-detection logic described in the game spec.
 *
 * Bearings between players are captured directly by the "bow to greet" handshake (each
 * player's raw azimuth when pointing at an opponent) rather than computed from seat
 * coordinates, so this object only needs angular-comparison helpers.
 */
object Bearing {

    /** Default aim tolerance: an attack lands only within ±25° of the true bearing. */
    const val DEFAULT_TOLERANCE_DEGREES = 25f

    private const val FULL_CIRCLE = 360f
    private const val HALF_CIRCLE = 180f

    /** Smallest signed-magnitude angular difference between two bearings, in `[0, 180]`. */
    fun angularDistance(a: Float, b: Float): Float {
        val diff = abs(((a - b) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE)
        return if (diff > HALF_CIRCLE) FULL_CIRCLE - diff else diff
    }

    /** True when [aim] points at [target] within [tolerance] degrees. */
    fun isOnTarget(aim: Float, target: Float, tolerance: Float = DEFAULT_TOLERANCE_DEGREES): Boolean =
        angularDistance(aim, target) <= tolerance
}
