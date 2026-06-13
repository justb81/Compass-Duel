package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.Position
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsPlayer
import com.justb81.compassduel.game.kids.KidsRoundStats
import com.justb81.compassduel.game.standard.DuelPlayer
import com.justb81.compassduel.net.protocol.GameEvent
import com.justb81.compassduel.net.protocol.GameSnapshot
import com.justb81.compassduel.net.protocol.PlayerAction
import com.justb81.compassduel.net.protocol.PlayerSnapshot
import com.justb81.compassduel.net.protocol.PlayerStatus
import com.justb81.compassduel.net.protocol.RoundPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Clock abstraction
// ---------------------------------------------------------------------------

/**
 * Abstraction over the wall clock so the engine can be driven by a fake clock
 * in unit tests without real delays.
 */
interface GameClock {
    /** Current epoch time in milliseconds. */
    fun nowMillis(): Long
}

// ---------------------------------------------------------------------------
// Setup types
// ---------------------------------------------------------------------------

/**
 * Per-player setup data supplied to [GameEngine.startRound].
 *
 * @param id Player id (1 = host; 2–4 = clients).
 * @param name Display name.
 * @param position Seat position on the floor-plan grid (used for bearing calculations).
 * @param element Chosen element (Standard Mode); null for Kids Mode or if not yet picked.
 * @param spriteId Chosen sprite index (Kids Mode); null otherwise.
 */
data class EnginePlayerSetup(
    val id: Int,
    val name: String,
    val position: Position,
    val element: Element? = null,
    val spriteId: Int? = null,
)

// ---------------------------------------------------------------------------
// Engine state
// ---------------------------------------------------------------------------

/**
 * Sealed engine state: one variant per mode, carrying mode-specific player lists.
 */
sealed interface EngineState {

    /**
     * Standard Mode in-round state.
     *
     * @param players Current state of every player in the round.
     */
    data class Standard(val players: List<DuelPlayer>) : EngineState

    /**
     * Kids Mode in-round state.
     *
     * @param players Current state of every player.
     * @param stats Accumulated per-player counters for award calculation.
     */
    data class Kids(
        val players: List<KidsPlayer>,
        val stats: Map<Int, KidsRoundStats>,
    ) : EngineState
}

// ---------------------------------------------------------------------------
// Tick I/O
// ---------------------------------------------------------------------------

/**
 * Discrete action submitted by a player during a tick (e.g. ATTACK or DODGE).
 *
 * @param playerId The acting player.
 * @param action The action type.
 * @param aimDegrees The player's aim azimuth at the moment the action fired.
 */
data class QueuedAction(
    val playerId: Int,
    val action: PlayerAction,
    val aimDegrees: Float,
)

/**
 * All input data the rule set receives for a single tick.
 *
 * @param continuousInputs Most recent continuous input per player id
 *   (aim direction + shield posture). Updated every 100 ms.
 * @param queuedActions Discrete actions that arrived since the last tick.
 *   Consumed exactly once per tick.
 */
data class TickInputs(
    val continuousInputs: Map<Int, ContinuousInput>,
    val queuedActions: List<QueuedAction>,
)

/**
 * Continuous per-player input: aim and posture.
 *
 * @param aimDegrees Calibrated aim azimuth in degrees [0, 360).
 * @param isShielding True when the device is in shield / magic-bubble posture.
 */
data class ContinuousInput(
    val aimDegrees: Float,
    val isShielding: Boolean,
)

/**
 * Output of one tick evaluation: the new state and any events generated.
 *
 * @param state The updated engine state after applying all inputs.
 * @param events Discrete events produced this tick (hits, catches, eliminations, …).
 * @param targetIds Map of player id → id of the opponent they are currently aiming at (within cone), or null.
 */
data class TickResult(
    val state: EngineState,
    val events: List<GameEvent>,
    val targetIds: Map<Int, Int?>,
)

// ---------------------------------------------------------------------------
// Round outcome
// ---------------------------------------------------------------------------

/**
 * The authoritative outcome of one round, computed when [ModeRuleSet.isRoundOver] returns true.
 */
sealed interface RoundOutcome {

    /**
     * Standard Mode round outcome.
     *
     * @param winnerId Id of the round winner, or null for a draw (timeout with tied HP).
     */
    data class StandardWinner(val winnerId: Int?) : RoundOutcome

    /**
     * Kids Mode round outcome (timer-only — everyone plays the whole round).
     *
     * @param stats Per-player counters accumulated over the round.
     * @param awards Awards assigned to every player.
     */
    data class KidsOutcome(
        val stats: Map<Int, KidsRoundStats>,
        val awards: Map<Int, KidsAward>,
    ) : RoundOutcome
}

// ---------------------------------------------------------------------------
// ModeRuleSet interface
// ---------------------------------------------------------------------------

/**
 * Strategy interface that encapsulates all mode-specific game rules.
 *
 * A [GameEngine] calls these methods on every tick; implementations delegate
 * to the pure domain functions in `game/standard/` and `game/kids/`.
 *
 * Implementations must be stateless: all state lives in [EngineState] and is
 * threaded through [onTick].
 */
interface ModeRuleSet {

    /** Aim cone half-width used for both hit detection and target-indicator. */
    val aimToleranceDegrees: Float

    /** Active-phase duration in seconds. */
    val roundDurationSeconds: Int

    /** Whether DODGE gestures are processed for this mode. */
    val dodgeEnabled: Boolean

    /**
     * Builds the initial [EngineState] from the per-player setup data.
     *
     * @param setup Player list in the order they joined; positions come from seat grid.
     */
    fun initialState(setup: List<EnginePlayerSetup>): EngineState

    /**
     * Applies all inputs for one tick and returns the new state plus any events.
     *
     * This is a pure function: it reads [state] and [inputs], returns a new state
     * and event list, and never mutates anything. The engine replaces its internal
     * state with the returned value.
     *
     * @param state Current engine state (must be the correct subtype for this rule set).
     * @param inputs All continuous and discrete inputs for this tick.
     * @param nowMillis Current epoch millis from [GameClock].
     * @param setup Original player setup, providing positions for bearing math.
     */
    fun onTick(
        state: EngineState,
        inputs: TickInputs,
        nowMillis: Long,
        setup: List<EnginePlayerSetup>,
    ): TickResult

    /**
     * Returns true when the round should end (e.g. last survivor found, or timer expired).
     *
     * @param state Current engine state.
     * @param elapsedMillis Milliseconds elapsed since the active phase started.
     */
    fun isRoundOver(state: EngineState, elapsedMillis: Long): Boolean

    /**
     * Computes the authoritative round outcome. Called once when [isRoundOver] returns true.
     *
     * @param state The final engine state.
     */
    fun roundOutcome(state: EngineState): RoundOutcome
}

// ---------------------------------------------------------------------------
// GameEngine
// ---------------------------------------------------------------------------

/**
 * Host-authoritative game engine that drives one round end-to-end.
 *
 * The engine owns the tick loop (every [tickMillis] ms), processes the input queue,
 * calls the active [ModeRuleSet] on every tick, and publishes the resulting
 * [GameSnapshot] to [snapshots].
 *
 * ### Lifecycle
 * 1. Construct once per session.
 * 2. Call [startRound] to begin a new round (COUNTDOWN → PLAYING → ROUND_OVER).
 * 3. Call [submitInput] from any thread to queue player actions.
 * 4. Collect [snapshots] to drive the UI and broadcast.
 * 5. Call [stop] when the session ends.
 *
 * ### Thread safety
 * [submitInput] is thread-safe. All internal state transitions happen on the
 * coroutine launched inside [startRound].
 *
 * @param rules The active mode rule set.
 * @param clock The wall-clock or fake clock for testing.
 * @param scope Coroutine scope that owns the tick loop.
 * @param tickMillis Tick interval in milliseconds. Defaults to 100 ms.
 */
open class GameEngine(
    private val rules: ModeRuleSet,
    private val clock: GameClock,
    private val scope: CoroutineScope,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
) {
    private val _snapshots = MutableStateFlow(initialSnapshot())
    /** Live stream of authoritative game state, updated every tick. */
    val snapshots: StateFlow<GameSnapshot> = _snapshots

    private var engineState: EngineState? = null
    private var setup: List<EnginePlayerSetup> = emptyList()
    private var roundIndex: Int = 0
    private var roundStartMillis: Long = 0L
    private var activePhaseStartMillis: Long = 0L
    private var sequenceNumber: Int = 0
    private var tickJob: Job? = null
    private var currentPhase: RoundPhase = RoundPhase.COUNTDOWN

    // Input queue — written from any thread, drained on the tick coroutine
    private val inputLock = Any()
    private val continuousInputs: MutableMap<Int, ContinuousInput> = mutableMapOf()
    private val queuedActions: ArrayDeque<QueuedAction> = ArrayDeque()

    /**
     * Starts a new round, resetting all state and beginning the COUNTDOWN phase.
     *
     * Any previously running tick loop is cancelled first.
     *
     * @param playerSetup Per-player seat and character data.
     * @param roundIndex Zero-based round index.
     */
    fun startRound(playerSetup: List<EnginePlayerSetup>, roundIndex: Int) {
        tickJob?.cancel()
        synchronized(inputLock) {
            continuousInputs.clear()
            queuedActions.clear()
        }

        setup = playerSetup
        this.roundIndex = roundIndex
        engineState = rules.initialState(playerSetup)
        roundStartMillis = clock.nowMillis()
        activePhaseStartMillis = roundStartMillis + COUNTDOWN_MILLIS
        currentPhase = RoundPhase.COUNTDOWN
        sequenceNumber = 0

        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(tickMillis)
            }
        }
    }

    /**
     * Submits a player input to the engine. Thread-safe.
     *
     * For continuous state (aim, shield posture) this replaces the previous value.
     * For discrete actions (ATTACK, DODGE) the action is queued and consumed exactly once.
     *
     * @param playerId The submitting player's id.
     * @param aimDegrees Calibrated aim azimuth.
     * @param isShielding True when the device is in shield posture.
     * @param action Optional discrete action; null means posture-only update.
     */
    fun submitInput(
        playerId: Int,
        aimDegrees: Float,
        isShielding: Boolean,
        action: PlayerAction? = null,
    ) {
        synchronized(inputLock) {
            continuousInputs[playerId] = ContinuousInput(aimDegrees, isShielding)
            if (action != null && action != PlayerAction.IDLE && action != PlayerAction.SHIELD) {
                queuedActions.addLast(QueuedAction(playerId, action, aimDegrees))
            }
        }
    }

    /**
     * Returns the authoritative outcome of the current round, or null when the round
     * has not yet ended.
     *
     * Only meaningful once [snapshots] has emitted a snapshot with
     * [com.justb81.compassduel.net.protocol.RoundPhase.ROUND_OVER].
     */
    open fun roundOutcome(): RoundOutcome? = engineState?.let { rules.roundOutcome(it) }

    /** Stops the tick loop and cancels the round. */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    // ---------------------------------------------------------------------------
    // Internal tick — public for testing (driven by FakeClock in tests)
    // ---------------------------------------------------------------------------

    /**
     * Executes one engine tick: reads inputs, delegates to the rule set, updates
     * the snapshot. Exposed for test-driving without a running coroutine.
     */
    fun tick() {
        val state = engineState ?: return
        val now = clock.nowMillis()

        // Drain the input queue under lock; release before calling the rule set
        val inputs: TickInputs
        synchronized(inputLock) {
            inputs = TickInputs(
                continuousInputs = continuousInputs.toMap(),
                queuedActions = queuedActions.toList(),
            )
            queuedActions.clear()
        }

        // --- Phase transitions ---
        val newPhase = computePhase(now)
        if (newPhase != currentPhase) {
            if (newPhase == RoundPhase.PLAYING) {
                activePhaseStartMillis = now
            }
            currentPhase = newPhase
        }

        val elapsed = (now - activePhaseStartMillis).coerceAtLeast(0L)

        // Only run rule logic during the active phase and when not already over
        val tickResult: TickResult = when (currentPhase) {
            RoundPhase.PLAYING -> {
                if (rules.isRoundOver(state, elapsed)) {
                    currentPhase = RoundPhase.ROUND_OVER
                    TickResult(state, emptyList(), emptyMap())
                } else {
                    val result = rules.onTick(state, inputs, now, setup)
                    engineState = result.state
                    // Check for round-over caused by the tick (e.g. survivor elimination)
                    if (rules.isRoundOver(result.state, elapsed)) {
                        currentPhase = RoundPhase.ROUND_OVER
                    }
                    result
                }
            }
            else -> TickResult(state, emptyList(), emptyMap())
        }
        val (updatedState, events, targetIds) = tickResult

        val remainingMillis = when (currentPhase) {
            RoundPhase.COUNTDOWN -> (activePhaseStartMillis - now).coerceAtLeast(0L)
            RoundPhase.PLAYING -> {
                val roundMillis = rules.roundDurationSeconds * MILLIS_PER_SECOND
                (activePhaseStartMillis + roundMillis - now).coerceAtLeast(0L)
            }
            RoundPhase.ROUND_OVER -> 0L
        }

        _snapshots.value = buildSnapshot(updatedState, currentPhase, remainingMillis, events, targetIds)
    }

    // ---------------------------------------------------------------------------
    // Snapshot builder
    // ---------------------------------------------------------------------------

    private fun buildSnapshot(
        state: EngineState,
        phase: RoundPhase,
        remainingMillis: Long,
        events: List<GameEvent>,
        targetIds: Map<Int, Int?>,
    ): GameSnapshot {
        val players = when (state) {
            is EngineState.Standard -> state.players.map { p ->
                PlayerSnapshot(
                    id = p.id,
                    hp = p.hp,
                    status = standardStatus(p),
                    targetId = targetIds[p.id],
                )
            }
            is EngineState.Kids -> state.players.map { p ->
                val now = clock.nowMillis()
                PlayerSnapshot(
                    id = p.id,
                    stars = p.stars,
                    status = kidsStatus(p, now),
                    targetId = targetIds[p.id],
                    restingUntilMillis = p.restingUntilMillis,
                )
            }
        }
        return GameSnapshot(
            seq = ++sequenceNumber,
            phase = phase,
            remainingMillis = remainingMillis,
            players = players,
            events = events,
        )
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun computePhase(nowMillis: Long): RoundPhase {
        if (currentPhase == RoundPhase.ROUND_OVER) return RoundPhase.ROUND_OVER
        return if (nowMillis < activePhaseStartMillis) RoundPhase.COUNTDOWN else RoundPhase.PLAYING
    }

    private fun standardStatus(player: DuelPlayer): PlayerStatus = when {
        player.isEliminated -> PlayerStatus.ELIMINATED
        player.isShielding -> PlayerStatus.SHIELDING
        else -> PlayerStatus.IDLE
    }

    private fun kidsStatus(player: KidsPlayer, nowMillis: Long): PlayerStatus = when {
        player.isResting(nowMillis) -> PlayerStatus.RESTING
        player.inBubble -> PlayerStatus.SHIELDING
        else -> PlayerStatus.IDLE
    }

    private fun initialSnapshot(): GameSnapshot = GameSnapshot(
        seq = 0,
        phase = RoundPhase.COUNTDOWN,
        remainingMillis = COUNTDOWN_MILLIS,
        players = emptyList(),
    )

    // ---------------------------------------------------------------------------
    // Imports for KidsPlayer.isResting (extension function defined in KidsMode.kt)
    // ---------------------------------------------------------------------------

    private fun KidsPlayer.isResting(nowMillis: Long): Boolean = nowMillis < restingUntilMillis

    companion object {
        private const val DEFAULT_TICK_MILLIS = 100L
        private const val COUNTDOWN_MILLIS = 3_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
