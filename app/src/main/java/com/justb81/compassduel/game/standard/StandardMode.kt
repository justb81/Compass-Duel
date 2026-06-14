package com.justb81.compassduel.game.standard

import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.elementModifier
import kotlin.math.roundToInt

/**
 * Constants that govern Standard ("Elemental Duel") mode.
 *
 * All tuning values live here so the game can be balanced by changing a
 * single file without touching any logic.
 */
object StandardRules {
    /** A player starts every round with this many hit points. */
    const val MAX_HP = 100

    /**
     * Default active-phase duration in seconds. The host can pick a different round length
     * in the lobby (see [com.justb81.compassduel.game.engine.StandardRuleSet]); this is the
     * fallback used when none is chosen.
     */
    const val ROUND_DURATION_SECONDS = 90

    /**
     * Total shield-time budget per player per round (ms) at the default round length: 50 % of
     * the round. Used as the [DuelPlayer.shieldRemainingMillis] default; the rule set scales it
     * to the chosen round length via [shieldBudgetMillis].
     */
    const val SHIELD_BUDGET_MILLIS = ROUND_DURATION_SECONDS * 1_000L / 2

    /** Minimum time (ms) between two consecutive attacks by the same player. */
    const val ATTACK_COOLDOWN_MILLIS = 700L

    /** Default rounds a player must win to take the match (best-of-3); host-configurable. */
    const val ROUNDS_TO_WIN = 2

    /**
     * Shield-time budget (ms) for a round of [roundSeconds] seconds: always 50 % of the round,
     * so the budget scales with the host's chosen round length.
     */
    fun shieldBudgetMillis(roundSeconds: Int): Long = roundSeconds * 1_000L / 2
}

/**
 * Host-side snapshot of one player in a Standard Mode round.
 *
 * All fields are immutable; game logic produces new instances rather than
 * mutating state in place, making the engine a pure function.
 *
 * @param id Unique player identifier (1 = host; 2–4 = clients in join order).
 * @param element The element chosen in the lobby; null before character selection.
 * @param hp Current hit points. Clamped to [0, [StandardRules.MAX_HP]].
 * @param isShielding True while the player holds the upright shield posture.
 * @param shieldRemainingMillis Remaining shield-time budget for the round (ms);
 *   starts at [StandardRules.SHIELD_BUDGET_MILLIS] and is consumed while shielding.
 * @param attackReadyAtMillis Epoch millis after which the next attack may fire (0 = ready immediately).
 */
data class DuelPlayer(
    val id: Int,
    val element: Element?,
    val hp: Int = StandardRules.MAX_HP,
    val isShielding: Boolean = false,
    val shieldRemainingMillis: Long = StandardRules.SHIELD_BUDGET_MILLIS,
    val attackReadyAtMillis: Long = 0L,
) {
    /** True when the player has been eliminated (HP reached zero). */
    val isEliminated: Boolean get() = hp <= 0
}

/**
 * Outcome of a single attack evaluation on the host.
 *
 * The host decides whether an attack lands or is blocked;
 * clients only receive the authoritative result.
 */
sealed interface AttackResult {

    /** The attacker's aim was outside the ±25° cone — nothing happens. */
    data object Missed : AttackResult

    /** The target was in shield posture — the attack is fully absorbed. */
    data object Blocked : AttackResult

    /**
     * The attack landed cleanly.
     * @param damage Final damage after the element modifier.
     */
    data class Hit(val damage: Int) : AttackResult
}

/**
 * Evaluates one attack from the host's authoritative game state.
 *
 * Priority order: off-cone → eliminated target → shielding → hit.
 *
 * @param aimAzimuth The attacker's reported aim direction in degrees [0, 360).
 * @param bearingToTarget The host-calculated bearing from the attacker's seat to the target's seat.
 * @param attackerElement The attacker's chosen element; null falls back to neutral damage.
 * @param target The target's current state.
 */
fun evaluateAttack(
    aimAzimuth: Float,
    bearingToTarget: Float,
    attackerElement: Element?,
    target: DuelPlayer,
): AttackResult {
    if (!Bearing.isOnTarget(aimAzimuth, bearingToTarget)) return AttackResult.Missed
    if (target.isEliminated) return AttackResult.Missed
    if (target.isShielding) return AttackResult.Blocked

    return AttackResult.Hit(computeDamage(attackerElement, target.element))
}

/**
 * Applies [damage] to [target], returning a new [DuelPlayer] with HP floored at 0.
 *
 * Never mutates the original; call-sites replace the player in their list.
 */
fun applyDamage(target: DuelPlayer, damage: Int): DuelPlayer =
    target.copy(hp = maxOf(0, target.hp - damage))

/**
 * Returns the id of the sole surviving (non-eliminated) player, or null when
 * zero or more than one player is still alive.
 *
 * A null return when all players are eliminated is treated as a draw by the engine.
 */
fun lastSurvivorId(players: List<DuelPlayer>): Int? {
    val alive = players.filter { !it.isEliminated }
    return if (alive.size == 1) alive.first().id else null
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private fun computeDamage(attacker: Element?, target: Element?): Int {
    if (attacker == null || target == null) return Element.BASE_DAMAGE
    val modifier = elementModifier(attacker, target)
    return (attacker.baseDamage * modifier).roundToInt()
}
