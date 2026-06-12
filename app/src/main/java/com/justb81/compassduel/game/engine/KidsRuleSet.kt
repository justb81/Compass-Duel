package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.kids.CatchResult
import com.justb81.compassduel.game.kids.KidsPlayer
import com.justb81.compassduel.game.kids.KidsRoundStats
import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.game.kids.assignAwards
import com.justb81.compassduel.game.kids.evaluateCatch
import com.justb81.compassduel.game.kids.starLeaderId
import com.justb81.compassduel.net.protocol.GameEvent
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.PlayerAction

/**
 * Kids Mode ("Star Catchers") rule set wired into the [GameEngine].
 *
 * Delegates all per-catch evaluation to the pure functions in `game/kids/`.
 * Adds engine-level concerns:
 * - Sparkle toss evaluation with rest-window and bubble-block checks.
 * - `KidsRoundStats` accumulation (stars, bubble blocks, sparkles thrown).
 * - REST_OVER event emission when rest windows expire.
 * - Round ends only on timer (no elimination in Kids Mode).
 * - Dodge is disabled; the wider 40° aim cone applies.
 */
class KidsRuleSet : ModeRuleSet {

    override val aimToleranceDegrees: Float = KidsRules.AIM_TOLERANCE_DEGREES
    override val roundDurationSeconds: Int = KidsRules.ROUND_DURATION_SECONDS
    override val dodgeEnabled: Boolean = false

    override fun initialState(setup: List<EnginePlayerSetup>): EngineState.Kids =
        EngineState.Kids(
            players = setup.map { s -> KidsPlayer(id = s.id) },
            stats = setup.associate { s ->
                s.id to KidsRoundStats(
                    playerId = s.id,
                    stars = 0,
                    bubbleBlocks = 0,
                    sparklesThrown = 0,
                )
            },
        )

    override fun onTick(
        state: EngineState,
        inputs: TickInputs,
        nowMillis: Long,
        setup: List<EnginePlayerSetup>,
    ): TickResult {
        require(state is EngineState.Kids) { "KidsRuleSet requires EngineState.Kids" }

        val events = mutableListOf<GameEvent>()
        var players = state.players
        var stats = state.stats

        // Apply continuous bubble (shield) posture
        players = players.map { player ->
            val input = inputs.continuousInputs[player.id]
            if (input != null) {
                player.copy(inBubble = input.isShielding)
            } else {
                player
            }
        }

        // Emit REST_OVER events for players whose rest window has expired this tick
        players = players.map { player ->
            if (player.restingUntilMillis > 0L && nowMillis >= player.restingUntilMillis) {
                events += GameEvent(GameEventType.REST_OVER, player.id)
                player.copy(restingUntilMillis = 0L)
            } else {
                player
            }
        }

        // Handle ATTACK (sparkle toss) gestures
        val leaderId = starLeaderId(players)
        for (action in inputs.queuedActions) {
            if (action.action != PlayerAction.ATTACK) continue
            val actorIndex = players.indexOfFirst { it.id == action.playerId }
            if (actorIndex < 0) continue

            // Accumulate sparkles thrown — every toss counts regardless of outcome
            stats = accumulateStat(stats, action.playerId) { it.copy(sparklesThrown = it.sparklesThrown + 1) }

            val actorSetup = setup.firstOrNull { it.id == action.playerId } ?: continue

            // Find the best on-cone target (smallest angular distance within tolerance)
            val targetEntry = players
                .filter { it.id != action.playerId }
                .mapNotNull { candidate ->
                    val candidateSetup = setup.firstOrNull { it.id == candidate.id } ?: return@mapNotNull null
                    val bearing = Bearing.calculate(actorSetup.position, candidateSetup.position)
                    val distance = Bearing.angularDistance(action.aimDegrees, bearing)
                    if (distance <= aimToleranceDegrees) candidate to distance else null
                }
                .minByOrNull { (_, distance) -> distance }

            if (targetEntry == null) {
                // No one in cone — miss
                continue
            }

            val (targetPlayer, _) = targetEntry
            val targetIndex = players.indexOfFirst { it.id == targetPlayer.id }
            val targetSetup = setup.first { it.id == targetPlayer.id }
            val bearing = Bearing.calculate(actorSetup.position, targetSetup.position)

            val result = evaluateCatch(
                aimAzimuth = action.aimDegrees,
                bearingToTarget = bearing,
                target = targetPlayer,
                targetIsStarLeader = leaderId == targetPlayer.id,
                nowMillis = nowMillis,
            )

            when (result) {
                is CatchResult.Caught -> {
                    events += GameEvent(GameEventType.CAUGHT, action.playerId, targetPlayer.id, result.catcherStars)
                    // Catcher earns stars
                    players = players.toMutableList().also {
                        val catcher = players[actorIndex]
                        it[actorIndex] = catcher.copy(stars = catcher.stars + result.catcherStars)
                    }
                    stats = accumulateStat(stats, action.playerId) { s -> s.copy(stars = s.stars + result.catcherStars) }
                    // Target enters rest window
                    players = players.toMutableList().also {
                        it[targetIndex] = targetPlayer.copy(restingUntilMillis = nowMillis + KidsRules.REST_AFTER_CAUGHT_MILLIS)
                    }
                }
                is CatchResult.Bubbled -> {
                    events += GameEvent(GameEventType.BUBBLED, action.playerId, targetPlayer.id, result.defenderStars)
                    // Defender earns a star
                    players = players.toMutableList().also {
                        it[targetIndex] = targetPlayer.copy(stars = targetPlayer.stars + result.defenderStars)
                    }
                    stats = accumulateStat(stats, targetPlayer.id) { s ->
                        s.copy(
                            stars = s.stars + result.defenderStars,
                            bubbleBlocks = s.bubbleBlocks + 1,
                        )
                    }
                }
                CatchResult.TargetResting -> {
                    // Toss fizzles — no event emitted
                }
                CatchResult.Missed -> {
                    // Outside cone — no event emitted
                }
            }
        }

        // Build target-id map for the sparkle-ring warning indicator
        val targetIds = buildTargetIds(players, inputs.continuousInputs, setup)

        return TickResult(
            state = EngineState.Kids(players, stats),
            events = events,
            targetIds = targetIds,
        )
    }

    override fun isRoundOver(state: EngineState, elapsedMillis: Long): Boolean {
        require(state is EngineState.Kids)
        return elapsedMillis >= roundDurationSeconds * MILLIS_PER_SECOND
    }

    override fun roundOutcome(state: EngineState): RoundOutcome {
        require(state is EngineState.Kids)
        val awards = assignAwards(state.stats.values.toList())
        return RoundOutcome.KidsOutcome(stats = state.stats, awards = awards)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun accumulateStat(
        stats: Map<Int, KidsRoundStats>,
        playerId: Int,
        update: (KidsRoundStats) -> KidsRoundStats,
    ): Map<Int, KidsRoundStats> {
        val existing = stats[playerId] ?: KidsRoundStats(playerId, 0, 0, 0)
        return stats + (playerId to update(existing))
    }

    /**
     * Builds a map from player id → id of the opponent currently in their sparkle cone.
     * Used by clients to display the "someone is aiming at you" sparkle-ring indicator.
     */
    private fun buildTargetIds(
        players: List<KidsPlayer>,
        continuousInputs: Map<Int, ContinuousInput>,
        setup: List<EnginePlayerSetup>,
    ): Map<Int, Int?> {
        return players.associate { actor ->
            val input = continuousInputs[actor.id]
            val targetId = if (input != null) {
                val actorSetup = setup.firstOrNull { it.id == actor.id }
                actorSetup?.let { actSetup ->
                    players
                        .filter { it.id != actor.id }
                        .mapNotNull { candidate ->
                            val candidateSetup = setup.firstOrNull { it.id == candidate.id } ?: return@mapNotNull null
                            val bearing = Bearing.calculate(actSetup.position, candidateSetup.position)
                            val distance = Bearing.angularDistance(input.aimDegrees, bearing)
                            if (distance <= aimToleranceDegrees) candidate.id to distance else null
                        }
                        .minByOrNull { (_, distance) -> distance }
                        ?.first
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
