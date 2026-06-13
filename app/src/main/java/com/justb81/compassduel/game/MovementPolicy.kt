package com.justb81.compassduel.game

/**
 * Verdict produced by [MovementPolicy] for a player's accumulated physical movement.
 */
enum class MovementVerdict {
    /** Movement is within the in-seat tolerance — no action. */
    OK,

    /** Movement exceeded the tolerance — warn the player they are leaving their spot. */
    WARN,

    /** Movement is sustained enough to invalidate the player's captured bearings. */
    FORFEIT,
}

/**
 * Pure decision rule that maps a player's accumulated physical movement to a
 * [MovementVerdict].
 *
 * Players establish their seat bearings once via the greeting handshake; those bearings
 * stay valid while the player stays put (a player merely *turning* in place does not
 * move their seat). Physical relocation — getting up and walking — invalidates them.
 * Movement is detected via Android's step detector and significant-motion trigger
 * (see [com.justb81.compassduel.sensor.MovementDetector]); GPS/gyro cannot sense a
 * sub-metre seat change.
 *
 * A short shuffle in the seat (a couple of steps) is tolerated; sustained stepping or a
 * significant-motion event forfeits, requiring the player to re-greet before the next round.
 */
object MovementPolicy {

    /** Accumulated steps that trigger a warning (still recoverable). */
    const val WARN_STEPS = 3

    /** Accumulated steps that force a forfeit (bearings invalidated). */
    const val FORFEIT_STEPS = 6

    /**
     * Evaluates a player's movement.
     *
     * @param accumulatedSteps Total steps detected for the player since their bearings
     *   were last established (reset on re-greet).
     * @param significantMotion True when the platform reported a significant-motion event
     *   (the user started moving meaningfully) — treated as an immediate forfeit.
     */
    fun evaluate(accumulatedSteps: Int, significantMotion: Boolean): MovementVerdict = when {
        significantMotion || accumulatedSteps >= FORFEIT_STEPS -> MovementVerdict.FORFEIT
        accumulatedSteps >= WARN_STEPS -> MovementVerdict.WARN
        else -> MovementVerdict.OK
    }
}
