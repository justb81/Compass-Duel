package com.justb81.compassduel.game.kids

import com.justb81.compassduel.game.Bearing

/**
 * Kids Mode ("Star Catchers") — the child-friendly variant of the duel.
 *
 * Instead of attacking and eliminating each other, players toss *sparkles* at
 * friends to catch a star from them. Nobody loses health and nobody drops out:
 * a round runs for a fixed time and every player keeps playing until the end.
 * See `docs/kids-mode-spec.md` for the full rule set and rationale.
 */
object KidsRules {

    /** Wider aim cone than the standard ±25° so younger players land catches reliably. */
    const val AIM_TOLERANCE_DEGREES = 40f

    /** Gentler shake threshold than the standard 2.5 m/s² — a soft wiggle is enough. */
    const val SHAKE_THRESHOLD_MPS2 = 1.5f

    /** A round lasts a fixed time; players are never eliminated. */
    const val ROUND_DURATION_SECONDS = 60

    /** After being caught, a player rests and cannot be caught again for this long. */
    const val REST_AFTER_CAUGHT_MILLIS = 3_000L

    /** Stars awarded to the catcher for a regular catch. */
    const val STARS_PER_CATCH = 1

    /** Catch-up rule: catching the current star leader awards extra stars. */
    const val STARS_PER_LEADER_CATCH = 2

    /** Blocking a sparkle with the magic bubble also earns the defender a star. */
    const val STARS_PER_BUBBLE_BLOCK = 1
}

/**
 * Host-side snapshot of one player in a Kids Mode round.
 *
 * There is intentionally no health and no element matchup: stars only ever go
 * up, and every sprite character is purely cosmetic.
 */
data class KidsPlayer(
    val id: Int,
    val stars: Int = 0,
    /** True while the player holds the phone flat ("magic bubble", the kids shield). */
    val inBubble: Boolean = false,
    /** Epoch millis until which the player rests after being caught (0 = not resting). */
    val restingUntilMillis: Long = 0L,
)

/** True while the player is in the post-catch rest window and cannot be caught. */
fun KidsPlayer.isResting(nowMillis: Long): Boolean = nowMillis < restingUntilMillis

/** Outcome of one sparkle toss, evaluated authoritatively on the host. */
sealed interface CatchResult {

    /** The toss was not aimed at the target closely enough. */
    data object Missed : CatchResult

    /** The target is resting after a recent catch; the toss fizzles harmlessly. */
    data object TargetResting : CatchResult

    /** The target's magic bubble blocked the sparkle — the defender earns stars. */
    data class Bubbled(val defenderStars: Int) : CatchResult

    /** The sparkle landed — the catcher earns stars. */
    data class Caught(val catcherStars: Int) : CatchResult
}

/**
 * Evaluates one sparkle toss from a player aiming at [aimAzimuth] toward a
 * target sitting at [bearingToTarget] (both in degrees, host-side bearings).
 *
 * Mirrors the standard-mode hit detection but with kid-friendly outcomes:
 * a wider aim cone, no damage, a rest window instead of HP loss, and a
 * catch-up bonus when the current star leader is caught.
 */
fun evaluateCatch(
    aimAzimuth: Float,
    bearingToTarget: Float,
    target: KidsPlayer,
    targetIsStarLeader: Boolean,
    nowMillis: Long,
): CatchResult {
    if (!Bearing.isOnTarget(aimAzimuth, bearingToTarget, KidsRules.AIM_TOLERANCE_DEGREES)) {
        return CatchResult.Missed
    }
    if (target.isResting(nowMillis)) return CatchResult.TargetResting
    if (target.inBubble) return CatchResult.Bubbled(KidsRules.STARS_PER_BUBBLE_BLOCK)

    val stars = if (targetIsStarLeader) KidsRules.STARS_PER_LEADER_CATCH else KidsRules.STARS_PER_CATCH
    return CatchResult.Caught(stars)
}

/**
 * The id of the player strictly ahead on stars, or `null` when the lead is
 * shared (no leader bonus on ties) or nobody has a star yet.
 */
fun starLeaderId(players: List<KidsPlayer>): Int? {
    val best = players.maxByOrNull { it.stars } ?: return null
    if (best.stars == 0) return null
    val tied = players.count { it.stars == best.stars }
    return if (tied == 1) best.id else null
}
