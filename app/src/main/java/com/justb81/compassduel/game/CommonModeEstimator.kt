package com.justb81.compassduel.game

/**
 * Estimates the **common-mode rotation** shared by all players' compass headings — the
 * rotation introduced when the whole vehicle turns (a train rounding a bend, a car
 * turning), which shifts every phone's heading by the same amount at the same time.
 *
 * ### Why this works
 * When the vehicle turns by θ, every phone's magnetic heading shifts by θ. A player's
 * *aiming*, by contrast, moves only their own heading. So the per-player drift since a
 * shared baseline is `θ + (that player's own aim change)`; the **median** drift across
 * all players rejects the few who are actively aiming and recovers θ. Subtracting θ from
 * a player's live heading re-aligns it with the greeting-time bearings.
 *
 * ### Limitations
 * The median can only reject outliers when there are at least [MIN_PLAYERS_FOR_CORRECTION]
 * players; with two players an aimer is indistinguishable from a turn, so [estimate]
 * returns `0f` (no correction) and the caller falls back to sensor fusion + re-greeting.
 *
 * Pure and stateless — the host holds the per-player baselines and passes the current
 * headings each tick.
 */
object CommonModeEstimator {

    private const val FULL_CIRCLE = 360f
    private const val HALF_CIRCLE = 180f

    /** Minimum number of common players required before any correction is applied. */
    const val MIN_PLAYERS_FOR_CORRECTION = 3

    /**
     * Estimates the shared rotation θ̂ (degrees, signed in `(-180, 180]`) between the
     * [baseline] headings captured at round start and the [current] headings this tick.
     *
     * Only player ids present in **both** maps contribute. Returns `0f` when fewer than
     * [MIN_PLAYERS_FOR_CORRECTION] players are common (correction disabled).
     */
    fun estimate(baseline: Map<Int, Float>, current: Map<Int, Float>): Float {
        val drifts = current.mapNotNull { (id, now) ->
            baseline[id]?.let { signedDelta(now, it) }
        }
        if (drifts.size < MIN_PLAYERS_FOR_CORRECTION) return 0f
        return median(drifts)
    }

    /** Signed angular delta `a − b` wrapped to `(-180, 180]`. */
    private fun signedDelta(a: Float, b: Float): Float {
        val diff = (a - b + FULL_CIRCLE) % FULL_CIRCLE
        return if (diff > HALF_CIRCLE) diff - FULL_CIRCLE else diff
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}
