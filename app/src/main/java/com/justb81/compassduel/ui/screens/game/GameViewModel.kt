package com.justb81.compassduel.ui.screens.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.BuildConfig
import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.game.standard.StandardRules
import com.justb81.compassduel.haptics.HapticFeedback
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.GameSnapshot
import com.justb81.compassduel.net.protocol.LobbyPlayer
import com.justb81.compassduel.net.protocol.PlayerSnapshot
import com.justb81.compassduel.net.protocol.PlayerStatus
import com.justb81.compassduel.net.protocol.RoundPhase
import com.justb81.compassduel.sensor.InputPipeline
import com.justb81.compassduel.sensor.MovementDetector
import com.justb81.compassduel.sensor.OrientationSensor
import com.justb81.compassduel.session.GameSession
import com.justb81.compassduel.ui.components.ActionEffect
import com.justb81.compassduel.ui.components.CompassTarget
import com.justb81.compassduel.ui.components.actionEffectKindFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

/** Per-player HUD data derived from [GameSnapshot] for display in the game screen. */
data class OpponentUiModel(
    val id: Int,
    val name: String,
    val hp: Int,
    val stars: Int,
    val isEliminated: Boolean,
)

/**
 * Sealed UI state for the game screen.
 *
 * The screen renders a different layout for each phase:
 * - [Countdown]: "get ready" overlay with counter.
 * - [Playing]: HUD + CompassRing + status line.
 * - [RoundOver]: brief "round ended" overlay (navigation to Results is handled
 *   by the nav graph via [SessionEvent.MatchOver]).
 */
sealed interface GameUiState {

    /**
     * COUNTDOWN phase — short "get ready" window before combat.
     *
     * @param secondsLeft Whole seconds remaining in the countdown.
     * @param mode Game mode (STANDARD or KIDS); drives the how-to-play overlay content.
     */
    data class Countdown(
        val secondsLeft: Int,
        val mode: GameMode,
    ) : GameUiState

    /**
     * PLAYING phase — active combat / star-catching.
     *
     * @param mode Game mode (STANDARD or KIDS).
     * @param myHp Local player HP (Standard only; 0 in Kids).
     * @param myStars Local player star count (Kids only; 0 in Standard).
     * @param myElement Element emoji+name label (Standard only; null in Kids).
     * @param mySpriteEmoji Sprite emoji (Kids only; null in Standard).
     * @param myStatus Name of the local player's [com.justb81.compassduel.net.protocol.PlayerStatus].
     * @param isEliminated True when the local player has been eliminated (Standard only).
     * @param compassTargets Opponent dots for the [com.justb81.compassduel.ui.components.CompassRing].
     * @param remainingMillis Milliseconds remaining in the round.
     * @param roundWins Round-win counts per player id (Standard only).
     * @param maxRoundWins Maximum round wins needed to win the match.
     * @param warningActive True when at least one opponent has us in their aim cone.
     * @param opponents Opponents for the HUD list.
     * @param flashEvent Transient overlay flash; null when no flash is active.
     * @param actionEffect Transient projectile/impact/defensive visual; null when none is active.
     * @param restingUntilMillis Epoch millis until which the local player is resting (Kids).
     *
     * High-frequency sensor-driven values (azimuth, shield-arm progress, debug aim) are
     * **not** here — they live in [CompassUiState] so a new sample only recomposes the
     * compass sub-tree, not the whole HUD (#71).
     */
    @Suppress("LongParameterList")
    data class Playing(
        val mode: GameMode,
        val myHp: Int,
        val myStars: Int,
        val myElement: String?,
        val mySpriteEmoji: String?,
        val myStatus: String,
        val isEliminated: Boolean,
        val compassTargets: List<CompassTarget>,
        val remainingMillis: Long,
        val roundWins: Map<Int, Int>,
        val maxRoundWins: Int,
        val warningActive: Boolean,
        val opponents: List<OpponentUiModel>,
        val flashEvent: FlashEvent?,
        val actionEffect: ActionEffect?,
        val shielding: Boolean,
        val shieldRemainingFraction: Float,
        val restingUntilMillis: Long,
    ) : GameUiState

    /** ROUND_OVER phase — match-over navigation is handled by the nav graph. */
    data object RoundOver : GameUiState
}

/**
 * High-frequency, sensor-rate slice of the game screen, kept separate from
 * [GameUiState.Playing] so a ~50 Hz orientation sample only recomposes the compass
 * sub-tree (ring, shield indicator, effect overlay) rather than the entire HUD (#71).
 *
 * @param azimuthDegrees Local player's raw azimuth in `[0, 360)`.
 * @param shieldArmProgress Local shield arming progress `[0, 1]` (loading ring).
 * @param debugAimDegrees Live aim in debug builds; null in release.
 */
data class CompassUiState(
    val azimuthDegrees: Float = 0f,
    val shieldArmProgress: Float = 0f,
    val debugAimDegrees: Float? = null,
)

/**
 * Transient overlay flash event for game screen feedback.
 *
 * [GREEN] = attack landed / sparkle caught (positive).
 * [RED] = damage received (Standard only — never used in Kids Mode).
 * [TWINKLE] = catch or bubble event (Kids Mode, soft yellow).
 */
enum class FlashEvent { GREEN, RED, TWINKLE }

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * ViewModel for the game screen.
 *
 * Combines the host-authoritative [GameSnapshot] with local sensor data so the
 * [com.justb81.compassduel.ui.components.CompassRing] updates at full sensor rate
 * while game logic stays at 10 Hz.
 *
 * ### Aim & bearings
 * Aim is the raw absolute azimuth (no calibration): when the phase transitions to
 * [RoundPhase.PLAYING] the ViewModel starts [InputPipeline], which reports the raw heading.
 * Opponent bearings come from the greeting handshake via [GameSession.myBearings] and drive
 * the compass ring and on-target highlighting.
 *
 * ### Movement
 * [MovementDetector] events are forwarded to the host via [GameSession.submitLocalMovement]
 * so the host can warn or forfeit a player who physically leaves their seat.
 *
 * ### Haptic dispatch
 * [GameSnapshot.events] from each new snapshot are scanned and mapped to
 * [HapticFeedback] calls, branching on mode so Kids Mode never receives
 * long buzzes or elimination patterns.
 */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val session: GameSession,
    private val orientationSensor: OrientationSensor,
    private val inputPipeline: InputPipeline,
    private val hapticFeedback: HapticFeedback,
    private val movementDetector: MovementDetector,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GameUiState>(
        GameUiState.Countdown(secondsLeft = INITIAL_COUNTDOWN_SECONDS, mode = GameMode.STANDARD),
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // High-frequency compass slice, updated at sensor rate. Kept separate from
    // _uiState so an orientation sample only recomposes the compass sub-tree (#71).
    private val _compassState = MutableStateFlow(CompassUiState())
    val compassState: StateFlow<CompassUiState> = _compassState.asStateFlow()

    // Single-slot timers for transient overlays (#74): a new value restarts the
    // auto-clear delay via collectLatest instead of launching a coroutine per event.
    private val flashSlot = MutableStateFlow<FlashEvent?>(null)
    private val effectSlot = MutableStateFlow<ActionEffect?>(null)

    // Latest raw azimuth from the sensor (updated at sensor rate).
    // Null until the first orientation sample has been received.
    private var latestRawAzimuth: Float? = null

    // Started once when COUNTDOWN transitions to PLAYING.
    private var pipelineStarted = false

    // The local player's outgoing greeting bearings (targetId -> degrees), from the host.
    private var myBearings: Map<Int, Float> = emptyMap()

    // Last known round-win counts from the previous round's RoundEnd message.
    // Persisted here so the HUD can show accumulated wins during PLAYING, when
    // session.roundEnd is null (it is cleared at round start).
    private var lastKnownRoundWins: Map<Int, Int> = emptyMap()

    // Local shield arming progress [0, 1], reported by the InputPipeline at sensor
    // rate so the center shield indicator can animate its "loading" ring.
    private var latestShieldArmProgress = 0f

    // Touch-mode input (always available alongside the motion gestures).
    // Whether the on-screen shield/bubble button is currently held; read by the
    // InputPipeline each cadence tick and OR-ed with the gesture shield.
    private val touchShieldHeld = MutableStateFlow(false)

    // One emission per on-screen fire (double-tap); the InputPipeline turns each
    // into an immediate ATTACK. extraBufferCapacity + DROP_OLDEST keeps tryEmit
    // non-suspending and lossless for the bursty taps a player can produce.
    private val touchFireRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Last processed snapshot sequence number (to avoid re-processing haptics)
    private var lastHapticSeq = -1

    // Monotonic id handed to each new ActionEffect so the overlay restarts its
    // animation even when the same effect kind repeats back-to-back.
    private var actionEffectTrigger = 0L

    init {
        observeSensors()
        observeSnapshot()
        observeRoundEnd()
        observeMyBearings()
        observeMovement()
        observeTransientEffects()
    }

    private fun observeMyBearings() {
        viewModelScope.launch {
            session.myBearings.collect { myBearings = it }
        }
    }

    private fun observeMovement() {
        viewModelScope.launch {
            movementDetector.events().collect { event ->
                session.submitLocalMovement(event.stepDelta, event.significant)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sensor observation
    // -------------------------------------------------------------------------

    private fun observeSensors() {
        viewModelScope.launch {
            orientationSensor.samples().collect { sample ->
                latestRawAzimuth = sample.azimuthDegrees

                // Update only the compass slice in real time — the HUD (driven by
                // _uiState at snapshot cadence) does not recompose on this (#71).
                _compassState.value = CompassUiState(
                    azimuthDegrees = sample.azimuthDegrees,
                    shieldArmProgress = latestShieldArmProgress,
                    debugAimDegrees = if (BuildConfig.DEBUG) sample.azimuthDegrees else null,
                )
            }
        }
    }

    /**
     * Single auto-clear timer per transient-overlay slot (#74). [collectLatest]
     * cancels the previous [delay] when a new flash/effect fires, so at most one
     * delay coroutine per slot is ever live — no per-event coroutine churn.
     */
    private fun observeTransientEffects() {
        viewModelScope.launch {
            flashSlot.collectLatest { flash ->
                if (flash == null) return@collectLatest
                delay(FLASH_DURATION_MILLIS)
                (_uiState.value as? GameUiState.Playing)
                    ?.takeIf { it.flashEvent == flash }
                    ?.let { _uiState.value = it.copy(flashEvent = null) }
            }
        }
        viewModelScope.launch {
            effectSlot.collectLatest { effect ->
                if (effect == null) return@collectLatest
                delay(ACTION_EFFECT_DURATION_MILLIS)
                (_uiState.value as? GameUiState.Playing)
                    ?.takeIf { it.actionEffect == effect }
                    ?.let { _uiState.value = it.copy(actionEffect = null) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot observation
    // -------------------------------------------------------------------------

    private fun observeSnapshot() {
        viewModelScope.launch {
            session.snapshot.collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                val lobby = session.lobby.value ?: return@collectLatest
                val mode = lobby.mode
                val myId = lobby.yourPlayerId
                val players = lobby.players

                when (snapshot.phase) {
                    RoundPhase.COUNTDOWN -> {
                        val secondsLeft = (snapshot.remainingMillis / MILLIS_PER_SECOND).toInt()
                        _uiState.value = GameUiState.Countdown(secondsLeft, mode)
                    }
                    RoundPhase.PLAYING -> {
                        // Start the input pipeline once; aim is the raw absolute azimuth.
                        if (!pipelineStarted) {
                            pipelineStarted = true
                            inputPipeline.start(
                                scope = viewModelScope,
                                playerId = myId,
                                mode = mode,
                                onInput = session::submitLocalInput,
                                onShieldArmProgress = { latestShieldArmProgress = it },
                                touchShieldHeld = { touchShieldHeld.value },
                                touchFireEvents = touchFireRequests,
                            )
                        }
                        // Dispatch haptics for new events
                        if (snapshot.seq != lastHapticSeq) {
                            lastHapticSeq = snapshot.seq
                            dispatchHaptics(snapshot, myId, mode)
                        }
                        _uiState.value = buildPlayingState(snapshot, players, mode, myId)
                    }
                    RoundPhase.ROUND_OVER -> {
                        stopPipeline()
                        latestRawAzimuth = null
                        _uiState.value = GameUiState.RoundOver
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Round-end observation (match score accumulation)
    // -------------------------------------------------------------------------

    /**
     * Observes [GameSession.roundEnd] to cache the latest match score so the
     * round-win HUD in [GameUiState.Playing] can show accumulated wins across rounds
     * (#75). [GameSession.roundEnd] is null during PLAYING (cleared at round start),
     * so the ViewModel keeps the last non-null match score locally.
     */
    private fun observeRoundEnd() {
        viewModelScope.launch {
            session.roundEnd.collect { roundEnd ->
                val score = roundEnd?.matchScore
                if (score != null && score.isNotEmpty()) {
                    lastKnownRoundWins = score
                    // Keep the live Playing state in sync so the HUD updates immediately.
                    val current = _uiState.value
                    if (current is GameUiState.Playing) {
                        _uiState.value = current.copy(roundWins = score)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI state builder
    // -------------------------------------------------------------------------

    private fun buildPlayingState(
        snapshot: GameSnapshot,
        lobbyPlayers: List<LobbyPlayer>,
        mode: GameMode,
        myId: Int,
    ): GameUiState.Playing {
        val mySnap = snapshot.players.firstOrNull { it.id == myId }
        val myLobby = lobbyPlayers.firstOrNull { it.id == myId }
        val tolerance = if (mode == GameMode.KIDS) KidsRules.AIM_TOLERANCE_DEGREES else Bearing.DEFAULT_TOLERANCE_DEGREES

        val compassTargets = buildCompassTargets(snapshot, lobbyPlayers, myId, tolerance)
        val opponents = buildOpponents(snapshot, lobbyPlayers, myId)
        val warningActive = snapshot.players.any { it.id != myId && it.targetId == myId }

        val myElement = if (mode == GameMode.STANDARD) myLobby?.element?.name?.let { ELEMENT_EMOJIS[it] } else null
        val mySpriteEmoji = if (mode == GameMode.KIDS) myLobby?.spriteId?.let { SPRITE_EMOJIS.getOrNull(it) } else null

        val flash = computeFlash(snapshot.events, myId, mode)
        val existingFlash = (_uiState.value as? GameUiState.Playing)?.flashEvent
        val resolvedFlash = flash ?: existingFlash

        if (flash != null) {
            // Arm the single-slot flash timer; collectLatest restarts the delay (#74).
            flashSlot.value = flash
        }

        val myStatus = mySnap?.status ?: PlayerStatus.IDLE
        val resolvedEffect = resolveActionEffect(snapshot, myId, mode, myLobby?.element)

        return GameUiState.Playing(
            mode = mode,
            myHp = mySnap?.hp ?: StandardRules.MAX_HP,
            myStars = mySnap?.stars ?: 0,
            myElement = myElement,
            mySpriteEmoji = mySpriteEmoji,
            myStatus = mySnap?.status?.name ?: PlayerStatus.IDLE.name,
            isEliminated = mySnap?.status == PlayerStatus.ELIMINATED,
            compassTargets = compassTargets,
            remainingMillis = snapshot.remainingMillis,
            roundWins = lastKnownRoundWins,
            maxRoundWins = StandardRules.ROUNDS_TO_WIN,
            warningActive = warningActive,
            opponents = opponents,
            flashEvent = resolvedFlash,
            actionEffect = resolvedEffect,
            shielding = myStatus == PlayerStatus.SHIELDING,
            shieldRemainingFraction = shieldRemainingFraction(mySnap),
            restingUntilMillis = mySnap?.restingUntilMillis ?: 0L,
        )
    }

    /** Remaining shield budget as a `[0, 1]` fraction of [StandardRules.SHIELD_BUDGET_MILLIS]. */
    private fun shieldRemainingFraction(snap: PlayerSnapshot?): Float =
        (snap?.shieldRemainingMillis ?: 0L).toFloat() / StandardRules.SHIELD_BUDGET_MILLIS

    private fun buildCompassTargets(
        snapshot: GameSnapshot,
        lobbyPlayers: List<LobbyPlayer>,
        myId: Int,
        tolerance: Float,
    ): List<CompassTarget> = snapshot.players
        .filter { it.id != myId }
        .mapIndexedNotNull { index, playerSnap ->
            val opponentLobby = lobbyPlayers.firstOrNull { it.id == playerSnap.id } ?: return@mapIndexedNotNull null
            val bearing = myBearings[playerSnap.id] ?: return@mapIndexedNotNull null
            val onTarget = Bearing.isOnTarget(latestRawAzimuth ?: 0f, bearing, tolerance)
            CompassTarget(
                id = playerSnap.id,
                name = opponentLobby.name,
                color = OPPONENT_COLORS[index % OPPONENT_COLORS.size],
                bearingDegrees = bearing,
                onTarget = onTarget,
            )
        }

    private fun buildOpponents(
        snapshot: GameSnapshot,
        lobbyPlayers: List<LobbyPlayer>,
        myId: Int,
    ): List<OpponentUiModel> = snapshot.players
        .filter { it.id != myId }
        .mapNotNull { ps ->
            val lp = lobbyPlayers.firstOrNull { it.id == ps.id } ?: return@mapNotNull null
            OpponentUiModel(
                id = ps.id,
                name = lp.name,
                hp = ps.hp,
                stars = ps.stars,
                isEliminated = ps.status == PlayerStatus.ELIMINATED,
            )
        }

    // -------------------------------------------------------------------------
    // Haptic dispatch
    // -------------------------------------------------------------------------

    private fun dispatchHaptics(snapshot: GameSnapshot, myId: Int, mode: GameMode) {
        snapshot.events.forEach { event ->
            when (mode) {
                GameMode.STANDARD -> dispatchStandardHaptic(event, myId)
                GameMode.KIDS -> dispatchKidsHaptic(event, myId)
            }
        }
        // Warn: someone is aiming at us
        if (snapshot.players.any { it.id != myId && it.targetId == myId }) {
            hapticFeedback.inCrosshairs()
        }
    }

    private fun dispatchStandardHaptic(
        event: com.justb81.compassduel.net.protocol.GameEvent,
        myId: Int,
    ) {
        when (event.type) {
            GameEventType.HIT -> {
                if (event.actorId == myId) hapticFeedback.hitLanded()
                if (event.targetId == myId) hapticFeedback.hitReceived()
            }
            GameEventType.BLOCKED -> {
                if (event.targetId == myId) hapticFeedback.blocked()
            }
            GameEventType.ELIMINATED -> {
                if (event.targetId == myId) hapticFeedback.eliminated()
            }
            else -> Unit
        }
    }

    private fun dispatchKidsHaptic(
        event: com.justb81.compassduel.net.protocol.GameEvent,
        myId: Int,
    ) {
        when (event.type) {
            GameEventType.CAUGHT -> {
                if (event.actorId == myId) hapticFeedback.kidsStar()
                if (event.targetId == myId) hapticFeedback.kidsCaught()
            }
            GameEventType.BUBBLED -> {
                if (event.targetId == myId) hapticFeedback.blocked()
            }
            else -> Unit
        }
    }

    // -------------------------------------------------------------------------
    // Flash helper
    // -------------------------------------------------------------------------

    private fun computeFlash(
        events: List<com.justb81.compassduel.net.protocol.GameEvent>,
        myId: Int,
        mode: GameMode,
    ): FlashEvent? = events.firstNotNullOfOrNull { event ->
        when {
            mode == GameMode.KIDS &&
                (event.type == GameEventType.CAUGHT || event.type == GameEventType.BUBBLED) ->
                FlashEvent.TWINKLE
            mode == GameMode.STANDARD &&
                event.type == GameEventType.HIT && event.actorId == myId ->
                FlashEvent.GREEN
            mode == GameMode.STANDARD &&
                event.type == GameEventType.HIT && event.targetId == myId ->
                FlashEvent.RED
            mode == GameMode.STANDARD &&
                event.type == GameEventType.BLOCKED && event.actorId == myId ->
                FlashEvent.GREEN
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Action-effect helper
    // -------------------------------------------------------------------------

    /**
     * Resolves the transient action effect for the current snapshot and schedules
     * it to clear after [ACTION_EFFECT_DURATION_MILLIS]; falls back to the effect
     * already showing when no new one fires. [rawElement] themes Standard-Mode
     * effects and is ignored in Kids Mode.
     *
     * The effect kind is derived purely from the host events via [actionEffectKindFor]
     * — the local player's own resolved attack drives the projectile, so no new
     * network payload is introduced.
     */
    private fun resolveActionEffect(
        snapshot: GameSnapshot,
        myId: Int,
        mode: GameMode,
        rawElement: Element?,
    ): ActionEffect? {
        val effectKind = snapshot.events.firstNotNullOfOrNull { actionEffectKindFor(it, myId, mode) }
        val element = if (mode == GameMode.STANDARD) rawElement else null
        val newEffect = effectKind?.let { kind ->
            actionEffectTrigger += 1
            ActionEffect(kind = kind, element = element, triggerId = actionEffectTrigger)
        }
        if (newEffect != null) {
            // Arm the single-slot effect timer; collectLatest restarts the delay (#74).
            effectSlot.value = newEffect
        }
        return newEffect ?: (_uiState.value as? GameUiState.Playing)?.actionEffect
    }

    // -------------------------------------------------------------------------
    // Touch-mode input (on-screen controls, always available)
    // -------------------------------------------------------------------------

    /**
     * Reports the on-screen shield/bubble button's held state. While true, the
     * [InputPipeline] reports the local player as shielding (Standard) or in a
     * magic bubble (Kids), in addition to the motion-gesture shield.
     */
    fun setTouchShield(held: Boolean) {
        touchShieldHeld.value = held
    }

    /**
     * Requests a single touch fire (double-tap). The [InputPipeline] emits an
     * ATTACK with the latest aim; the host enforces the per-mode cooldown, so
     * rapid taps cannot fire faster than a swing.
     */
    fun fireTouch() {
        touchFireRequests.tryEmit(Unit)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private fun stopPipeline() {
        if (pipelineStarted) {
            inputPipeline.stop()
            pipelineStarted = false
        }
        // Clear any held touch shield so it never leaks across rounds.
        touchShieldHeld.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopPipeline()
    }

    companion object {
        private const val INITIAL_COUNTDOWN_SECONDS = 3
        private const val MILLIS_PER_SECOND = 1_000L
        private const val FLASH_DURATION_MILLIS = 500L

        // Must outlast the overlay's flight + impact animation so the effect state
        // is not cleared mid-sequence (see ActionEffects EFFECT_DURATION_MILLIS = 600).
        private const val ACTION_EFFECT_DURATION_MILLIS = 650L

        /** Emoji + name label for each element (keyed by enum name). */
        private val ELEMENT_EMOJIS = mapOf(
            "FIRE" to "🔥 Fire",
            "WATER" to "💧 Water",
            "EARTH" to "🌿 Earth",
            "LIGHTNING" to "⚡ Lightning",
        )

        /** Emoji for Kids Mode sprite ids. */
        private val SPRITE_EMOJIS = listOf("⭐", "🌙", "☀️", "☄️")

        /** Colours assigned to opponents in order. Cycles if more than 3 opponents. */
        private val OPPONENT_COLORS = listOf(
            androidx.compose.ui.graphics.Color(0xFFE53935), // Red
            androidx.compose.ui.graphics.Color(0xFF1E88E5), // Blue
            androidx.compose.ui.graphics.Color(0xFF43A047), // Green
        )
    }
}
