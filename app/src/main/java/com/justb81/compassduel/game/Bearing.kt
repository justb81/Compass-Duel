package com.justb81.compassduel.game

import kotlin.math.abs
import kotlin.math.atan2

/** A 2D seat position on the floor plan used to compute bearings between players. */
data class Position(val x: Float, val y: Float)

/** Geometry helpers for the host-side hit-detection logic described in the game spec. */
object Bearing {

    /** Default aim tolerance: an attack lands only within ±25° of the true bearing. */
    const val DEFAULT_TOLERANCE_DEGREES = 25f

    private const val FULL_CIRCLE = 360f
    private const val HALF_CIRCLE = 180f

    /**
     * Bearing in degrees (0° = +y / "north") from [from] toward [to], clockwise,
     * normalised to the range `[0, 360)`.
     */
    fun calculate(from: Position, to: Position): Float {
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val degrees = Math.toDegrees(atan2(dx, dy)).toFloat()
        return (degrees + FULL_CIRCLE) % FULL_CIRCLE
    }

    /** Smallest signed-magnitude angular difference between two bearings, in `[0, 180]`. */
    fun angularDistance(a: Float, b: Float): Float {
        val diff = abs(((a - b) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE)
        return if (diff > HALF_CIRCLE) FULL_CIRCLE - diff else diff
    }

    /** True when [aim] points at [target] within [tolerance] degrees. */
    fun isOnTarget(aim: Float, target: Float, tolerance: Float = DEFAULT_TOLERANCE_DEGREES): Boolean =
        angularDistance(aim, target) <= tolerance
}
