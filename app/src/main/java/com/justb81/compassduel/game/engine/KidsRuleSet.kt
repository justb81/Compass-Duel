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
 * - The wider 40° aim cone applies.
 */
class KidsRuleSet : ModeRuleSet {

    override val aimToleranceDegrees: Float = KidsRules.AIM_TOLERANCE_DEGREES
    override val roundDurationSeconds: Int = KidsRules.ROUND_DURATION_SECONDS

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
        var players = applyBubblePosture(state.players, inputs.continuousInputs)
        players = emitRestOver(players, nowMillis, events)

        var stats = state.stats
        val leaderId = starLeaderId(players)
        inputs.queuedActions.filter { it.action == PlayerAction.ATTACK }.forEach { action ->
            val outcome = applyToss(players, stats, action, leaderId, nowMillis, setup, events)
            players = outcome.players
            stats = outcome.stats
        }

        val targetIds = buildTargetIds(players, inputs.continuousInputs, setup)
        return TickResult(
            state = EngineState.Kids(players, stats),
            events = events,
            targetIds = targetIds,
        )
    }

    /** Applies the latest magic-bubble (shield) posture to every player. */
    private fun applyBubblePosture(
        players: List<KidsPlayer>,
        continuousInputs: Map<Int, ContinuousInput>,
    ): List<KidsPlayer> = players.map { player ->
        val input = continuousInputs[player.id]
        if (input != null) player.copy(inBubble = input.isShielding) else player
    }

    /** Clears expired rest windows, emitting a REST_OVER event for each player that wakes up. */
    private fun emitRestOver(
        players: List<KidsPlayer>,
        nowMillis: Long,
        events: MutableList<GameEvent>,
    ): List<KidsPlayer> = players.map { player ->
        if (player.restingUntilMillis > 0L && nowMillis >= player.restingUntilMillis) {
            events += GameEvent(GameEventType.REST_OVER, player.id)
            player.copy(restingUntilMillis = 0L)
        } else {
            player
        }
    }

    /**
     * Evaluates a single sparkle toss: enforces the host-side toss cooldown, counts the
     * throw, finds the best on-cone target, and resolves the catch.
     * Returns the updated players and stats.
     */
    private fun applyToss(
        players: List<KidsPlayer>,
        stats: Map<Int, KidsRoundStats>,
        action: QueuedAction,
        leaderId: Int?,
        nowMillis: Long,
        setup: List<EnginePlayerSetup>,
        events: MutableList<GameEvent>,
    ): KidsTickState {
        val actorIndex = players.indexOfFirst { it.id == action.playerId }
        if (actorIndex < 0) return KidsTickState(players, stats)

        // Host-side cooldown guard — mirrors StandardRuleSet.applyOneAttack.
        val actor = players[actorIndex]
        if (nowMillis < actor.tossReadyAtMillis) return KidsTickState(players, stats)

        // Mark the cooldown window on the actor before evaluating the toss outcome.
        val updatedActor = actor.copy(tossReadyAtMillis = nowMillis + KidsRules.TOSS_COOLDOWN_MILLIS)
        val playersWithCooldown = players.toMutableList().also { it[actorIndex] = updatedActor }

        // Every toss counts toward sparkles thrown, regardless of outcome.
        val thrownStats = accumulateStat(stats, action.playerId) { it.copy(sparklesThrown = it.sparklesThrown + 1) }
        val actorSetup = setup.firstOrNull { it.id == action.playerId }
        val target = actorSetup?.let { selectTarget(action.playerId, action.aimDegrees, playersWithCooldown, it) }
        if (actorSetup == null || target == null) return KidsTickState(playersWithCooldown, thrownStats)

        val bearing = actorSetup.bearings[target.id] ?: return KidsTickState(playersWithCooldown, thrownStats)
        val result = evaluateCatch(
            aimAzimuth = action.aimDegrees,
            bearingToTarget = bearing,
            target = target,
            targetIsStarLeader = leaderId == target.id,
            nowMillis = nowMillis,
        )
        return resolveCatch(result, playersWithCooldown, thrownStats, action.playerId, actorIndex, target, nowMillis, events)
    }

    @Suppress("LongParameterList")
    private fun resolveCatch(
        result: CatchResult,
        players: List<KidsPlayer>,
        stats: Map<Int, KidsRoundStats>,
        catcherId: Int,
        catcherIndex: Int,
        target: KidsPlayer,
        nowMillis: Long,
        events: MutableList<GameEvent>,
    ): KidsTickState {
        val targetIndex = players.indexOfFirst { it.id == target.id }
        return when (result) {
            is CatchResult.Caught -> {
                events += GameEvent(GameEventType.CAUGHT, catcherId, target.id, result.catcherStars)
                val updated = players.toMutableList()
                val catcher = updated[catcherIndex]
                updated[catcherIndex] = catcher.copy(stars = catcher.stars + result.catcherStars)
                updated[targetIndex] = target.copy(restingUntilMillis = nowMillis + KidsRules.REST_AFTER_CAUGHT_MILLIS)
                val newStats = accumulateStat(stats, catcherId) { it.copy(stars = it.stars + result.catcherStars) }
                KidsTickState(updated, newStats)
            }
            is CatchResult.Bubbled -> {
                events += GameEvent(GameEventType.BUBBLED, catcherId, target.id, result.defenderStars)
                val updated = players.toMutableList()
                updated[targetIndex] = target.copy(stars = target.stars + result.defenderStars)
                val newStats = accumulateStat(stats, target.id) { s ->
                    s.copy(stars = s.stars + result.defenderStars, bubbleBlocks = s.bubbleBlocks + 1)
                }
                KidsTickState(updated, newStats)
            }
            CatchResult.TargetResting, CatchResult.Missed -> KidsTickState(players, stats)
        }
    }

    /** Returns the on-cone player nearest to [aimDegrees], or null when none is within tolerance. */
    private fun selectTarget(
        actorId: Int,
        aimDegrees: Float,
        players: List<KidsPlayer>,
        actorSetup: EnginePlayerSetup,
    ): KidsPlayer? = players
        .filter { it.id != actorId }
        .mapNotNull { candidate ->
            val bearing = actorSetup.bearings[candidate.id] ?: return@mapNotNull null
            val distance = Bearing.angularDistance(aimDegrees, bearing)
            if (distance <= aimToleranceDegrees) candidate to distance else null
        }
        .minByOrNull { (_, distance) -> distance }
        ?.first

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
    ): Map<Int, Int?> = players.associate { actor ->
        val input = continuousInputs[actor.id]
        val actorSetup = setup.firstOrNull { it.id == actor.id }
        val targetId = if (input != null && actorSetup != null) {
            selectTarget(actor.id, input.aimDegrees, players, actorSetup)?.id
        } else {
            null
        }
        actor.id to targetId
    }

    /** Updated players and stats produced by resolving a single sparkle toss. */
    private data class KidsTickState(
        val players: List<KidsPlayer>,
        val stats: Map<Int, KidsRoundStats>,
    )

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
