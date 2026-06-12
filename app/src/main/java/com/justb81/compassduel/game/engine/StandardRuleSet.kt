package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.standard.AttackResult
import com.justb81.compassduel.game.standard.DuelPlayer
import com.justb81.compassduel.game.standard.StandardRules
import com.justb81.compassduel.game.standard.applyDamage
import com.justb81.compassduel.game.standard.evaluateAttack
import com.justb81.compassduel.game.standard.lastSurvivorId
import com.justb81.compassduel.net.protocol.GameEvent
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.PlayerAction

/**
 * Standard ("Elemental Duel") rule set wired into the [GameEngine].
 *
 * Delegates all per-attack evaluation to the pure functions in
 * `game/standard/StandardMode.kt`. Adds engine-level concerns:
 * - Target selection: on-cone alive opponent with the smallest angular distance.
 * - Attack and dodge cooldown enforcement.
 * - Shield posture application.
 * - Round-over detection: last survivor or timer expired.
 * - Timeout outcome: highest HP wins; exact tie → draw (null winner id).
 */
class StandardRuleSet : ModeRuleSet {

    override val aimToleranceDegrees: Float = Bearing.DEFAULT_TOLERANCE_DEGREES
    override val roundDurationSeconds: Int = StandardRules.ROUND_DURATION_SECONDS
    override val dodgeEnabled: Boolean = true

    override fun initialState(setup: List<EnginePlayerSetup>): EngineState.Standard =
        EngineState.Standard(
            players = setup.map { s ->
                DuelPlayer(id = s.id, element = s.element)
            }
        )

    override fun onTick(
        state: EngineState,
        inputs: TickInputs,
        nowMillis: Long,
        setup: List<EnginePlayerSetup>,
    ): TickResult {
        require(state is EngineState.Standard) { "StandardRuleSet requires EngineState.Standard" }

        val events = mutableListOf<GameEvent>()
        var players = state.players

        // Apply continuous shield posture
        players = players.map { player ->
            val input = inputs.continuousInputs[player.id]
            if (input != null) {
                player.copy(isShielding = input.isShielding && !player.isEliminated)
            } else {
                player
            }
        }

        // Handle DODGE gestures
        for (action in inputs.queuedActions) {
            if (action.action != PlayerAction.DODGE) continue
            val actorIndex = players.indexOfFirst { it.id == action.playerId }
            if (actorIndex < 0) continue
            val actor = players[actorIndex]
            if (actor.isEliminated) continue
            if (nowMillis < actor.dodgeReadyAtMillis) continue // cooldown active

            players = players.toMutableList().also {
                it[actorIndex] = actor.copy(
                    dodgeActiveUntilMillis = nowMillis + StandardRules.DODGE_ACTIVE_MILLIS,
                    dodgeReadyAtMillis = nowMillis + StandardRules.DODGE_ACTIVE_MILLIS + StandardRules.DODGE_COOLDOWN_MILLIS,
                )
            }
        }

        // Handle ATTACK gestures
        for (action in inputs.queuedActions) {
            if (action.action != PlayerAction.ATTACK) continue
            val actorIndex = players.indexOfFirst { it.id == action.playerId }
            if (actorIndex < 0) continue
            val actor = players[actorIndex]
            if (actor.isEliminated) continue
            if (nowMillis < actor.attackReadyAtMillis) continue // cooldown active

            // Find target: on-cone alive opponent with smallest angular distance
            val actorSetup = setup.firstOrNull { it.id == actor.id } ?: continue
            val targetPlayer = selectTarget(actor.id, action.aimDegrees, players, setup, actorSetup) ?: continue

            val targetIndex = players.indexOfFirst { it.id == targetPlayer.id }
            val bearing = Bearing.calculate(actorSetup.position, setup.first { it.id == targetPlayer.id }.position)

            val result = evaluateAttack(
                aimAzimuth = action.aimDegrees,
                bearingToTarget = bearing,
                attackerElement = actor.element,
                target = targetPlayer,
                nowMillis = nowMillis,
            )

            // Mark attacker as having used their attack slot
            players = players.toMutableList().also {
                it[actorIndex] = actor.copy(
                    attackReadyAtMillis = nowMillis + StandardRules.ATTACK_COOLDOWN_MILLIS,
                )
            }

            when (result) {
                is AttackResult.Hit -> {
                    events += GameEvent(GameEventType.HIT, actor.id, targetPlayer.id, result.damage)
                    val damaged = applyDamage(players[targetIndex], result.damage)
                    players = players.toMutableList().also { it[targetIndex] = damaged }
                    if (damaged.isEliminated) {
                        events += GameEvent(GameEventType.ELIMINATED, targetPlayer.id)
                    }
                }
                is AttackResult.Dodged -> {
                    events += GameEvent(GameEventType.DODGED, actor.id, targetPlayer.id, result.damage)
                    val damaged = applyDamage(players[targetIndex], result.damage)
                    players = players.toMutableList().also { it[targetIndex] = damaged }
                    if (damaged.isEliminated) {
                        events += GameEvent(GameEventType.ELIMINATED, targetPlayer.id)
                    }
                }
                is AttackResult.Blocked -> {
                    events += GameEvent(GameEventType.BLOCKED, actor.id, targetPlayer.id)
                }
                is AttackResult.Missed -> {
                    events += GameEvent(GameEventType.MISS, actor.id, targetPlayer.id)
                }
            }
        }

        // Build target-id map for the warning indicator
        val targetIds = buildTargetIds(players, inputs.continuousInputs, setup)

        return TickResult(
            state = EngineState.Standard(players),
            events = events,
            targetIds = targetIds,
        )
    }

    override fun isRoundOver(state: EngineState, elapsedMillis: Long): Boolean {
        require(state is EngineState.Standard)
        val timerExpired = elapsedMillis >= roundDurationSeconds * MILLIS_PER_SECOND
        val onlyOneSurvivor = lastSurvivorId(state.players) != null
        val allEliminated = state.players.all { it.isEliminated }
        return timerExpired || onlyOneSurvivor || allEliminated
    }

    override fun roundOutcome(state: EngineState): RoundOutcome {
        require(state is EngineState.Standard)
        val survivor = lastSurvivorId(state.players)
        if (survivor != null) return RoundOutcome.StandardWinner(survivor)

        // All eliminated (simultaneous KO) → draw
        if (state.players.all { it.isEliminated }) return RoundOutcome.StandardWinner(null)

        // Timer expired — highest HP wins; exact tie → draw
        val maxHp = state.players.filter { !it.isEliminated }.maxOfOrNull { it.hp } ?: return RoundOutcome.StandardWinner(null)
        val highestHpPlayers = state.players.filter { !it.isEliminated && it.hp == maxHp }
        return if (highestHpPlayers.size == 1) {
            RoundOutcome.StandardWinner(highestHpPlayers.first().id)
        } else {
            RoundOutcome.StandardWinner(null)
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Selects the alive on-cone opponent with the smallest angular distance to [aimDegrees].
     * Returns null when no alive opponents are within the aim cone.
     */
    private fun selectTarget(
        actorId: Int,
        aimDegrees: Float,
        players: List<DuelPlayer>,
        setup: List<EnginePlayerSetup>,
        actorSetup: EnginePlayerSetup,
    ): DuelPlayer? {
        return players
            .filter { it.id != actorId && !it.isEliminated }
            .mapNotNull { candidate ->
                val candidateSetup = setup.firstOrNull { it.id == candidate.id } ?: return@mapNotNull null
                val bearing = Bearing.calculate(actorSetup.position, candidateSetup.position)
                val distance = Bearing.angularDistance(aimDegrees, bearing)
                if (distance <= aimToleranceDegrees) candidate to distance else null
            }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /**
     * Builds a map from player id → id of the opponent currently in their aim cone.
     * Used by clients to display the "someone is aiming at you" warning.
     */
    private fun buildTargetIds(
        players: List<DuelPlayer>,
        continuousInputs: Map<Int, ContinuousInput>,
        setup: List<EnginePlayerSetup>,
    ): Map<Int, Int?> {
        return players.associate { actor ->
            val input = continuousInputs[actor.id]
            val targetId = if (input != null && !actor.isEliminated) {
                val actorSetup = setup.firstOrNull { it.id == actor.id }
                if (actorSetup != null) {
                    selectTarget(actor.id, input.aimDegrees, players, setup, actorSetup)?.id
                } else {
                    null
                }
            } else {
                null
            }
            actor.id to targetId
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
