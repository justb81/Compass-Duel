package com.justb81.compassduel.ui.screens.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.BuildConfig
import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.Position
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
import com.justb81.compassduel.sensor.AimCalibration
import com.justb81.compassduel.sensor.AimCalibrationStore
import com.justb81.compassduel.sensor.InputPipeline
import com.justb81.compassduel.sensor.OrientationSensor
import com.justb81.compassduel.session.GameSession
import com.justb81.compassduel.ui.components.ActionEffect
import com.justb81.compassduel.ui.components.ActionEffectKind
import com.justb81.compassduel.ui.components.CompassTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
 * - [Countdown]: facing-capture overlay with counter.
 * - [Playing]: HUD + CompassRing + status line.
 * - [RoundOver]: brief "round ended" overlay (navigation to Results is handled
 *   by the nav graph via [SessionEvent.MatchOver]).
 */
sealed interface GameUiState {

    /**
     * COUNTDOWN phase — players capture their facing offset.
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
     * @param azimuthDegrees Local player's raw azimuth at sensor rate.
     * @param remainingMillis Milliseconds remaining in the round.
     * @param roundWins Round-win counts per player id (Standard only).
     * @param maxRoundWins Maximum round wins needed to win the match.
     * @param warningActive True when at least one opponent has us in their aim cone.
     * @param opponents Opponents for the HUD list.
     * @param flashEvent Transient overlay flash; null when no flash is active.
     * @param actionEffect Transient projectile/impact/defensive visual; null when none is active.
     * @param restingUntilMillis Epoch millis until which the local player is resting (Kids).
     * @param debugAimDegrees Calibrated aim in debug builds; null in release.
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
        val azimuthDegrees: Float,
        val remainingMillis: Long,
        val roundWins: Map<Int, Int>,
        val maxRoundWins: Int,
        val warningActive: Boolean,
        val opponents: List<OpponentUiModel>,
        val flashEvent: FlashEvent?,
        val actionEffect: ActionEffect?,
        val shielding: Boolean,
        val shieldArmProgress: Float,
        val shieldRemainingFraction: Float,
        val restingUntilMillis: Long,
        val debugAimDegrees: Float?,
    ) : GameUiState

    /** ROUND_OVER phase — match-over navigation is handled by the nav graph. */
    data object RoundOver : GameUiState
}

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
 * ### Calibration flow
 * Players preferably calibrate in the lobby (see
 * [com.justb81.compassduel.ui.screens.lobby.LobbyViewModel]); that offset is held in
 * [AimCalibrationStore]. When the phase transitions to [RoundPhase.PLAYING] the ViewModel
 * uses the lobby-captured [AimCalibration] if present, otherwise it falls back to capturing
 * the latest raw azimuth from [OrientationSensor] at the buzzer. Either way it then starts
 * [InputPipeline] with the result.
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
    private val calibrationStore: AimCalibrationStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GameUiState>(
        GameUiState.Countdown(secondsLeft = INITIAL_COUNTDOWN_SECONDS, mode = GameMode.STANDARD),
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // Latest raw azimuth and pitch from the sensor (updated at sensor rate).
    // Null until the first orientation sample has been received — used to prevent
    // a stale/zero azimuth from being captured as the aim-calibration fallback (#69).
    private var latestRawAzimuth: Float? = null
    private var latestPitch = 0f

    // Set once when COUNTDOWN transitions to PLAYING
    private var calibration: AimCalibration? = null
    private var pipelineStarted = false

    // Whether a PLAYING snapshot arrived before the first sensor sample.
    // When true the calibration will be captured on the first orientation reading
    // instead of at the PLAYING-phase transition.
    private var pendingCalibrationCapture = false

    // Last known round-win counts from the previous round's RoundEnd message.
    // Persisted here so the HUD can show accumulated wins during PLAYING, when
    // session.roundEnd is null (it is cleared at round start).
    private var lastKnownRoundWins: Map<Int, Int> = emptyMap()

    // Current calibrated aim for the compass ring
    private var calibratedAim = 0f

    // Local shield arming progress [0, 1], reported by the InputPipeline at sensor
    // rate so the center shield indicator can animate its "loading" ring.
    private var latestShieldArmProgress = 0f

    // Last processed snapshot sequence number (to avoid re-processing haptics)
    private var lastHapticSeq = -1

    // Monotonic id handed to each new ActionEffect so the overlay restarts its
    // animation even when the same effect kind repeats back-to-back.
    private var actionEffectTrigger = 0L

    // Local player's status in the previous snapshot, used to fire the
    // projectile effect on the IDLE/RESTING -> ATTACKING transition (the host
    // emits no "attack started" event, so we read it off the status it already
    // reports in every snapshot).
    private var lastStatus = PlayerStatus.IDLE

    init {
        observeSensors()
        observeSnapshot()
        observeRoundEnd()
    }

    // -------------------------------------------------------------------------
    // Sensor observation
    // -------------------------------------------------------------------------

    private fun observeSensors() {
        viewModelScope.launch {
            orientationSensor.samples().collect { sample ->
                latestRawAzimuth = sample.azimuthDegrees
                latestPitch = sample.pitchDegrees

                // If the PLAYING snapshot arrived before this first orientation sample,
                // capture the calibration now using the real azimuth (#69).
                if (pendingCalibrationCapture) {
                    pendingCalibrationCapture = false
                    val lobby = session.lobby.value
                    val mode = lobby?.mode ?: GameMode.STANDARD
                    val myId = lobby?.yourPlayerId ?: return@collect
                    // resolveCalibration always yields UseNow here because rawAzimuth != null.
                    val decision = resolveCalibration(
                        storedCal = calibrationStore.calibration.value,
                        rawAzimuth = sample.azimuthDegrees,
                    )
                    val cal = (decision as CalibrationDecision.UseNow).calibration
                    calibration = cal
                    calibratedAim = cal.calibrate(sample.azimuthDegrees)
                    pipelineStarted = true
                    inputPipeline.start(
                        scope = viewModelScope,
                        playerId = myId,
                        mode = mode,
                        calibration = cal,
                        onInput = session::submitLocalInput,
                        onShieldArmProgress = { latestShieldArmProgress = it },
                    )
                }

                val cal = calibration
                if (cal != null) {
                    calibratedAim = cal.calibrate(sample.azimuthDegrees)
                }
                // Update the compass ring in real time during PLAYING
                val current = _uiState.value
                if (current is GameUiState.Playing) {
                    _uiState.value = current.copy(
                        azimuthDegrees = sample.azimuthDegrees,
                        shieldArmProgress = latestShieldArmProgress,
                        debugAimDegrees = if (BuildConfig.DEBUG) calibratedAim else null,
                    )
                }
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
                        // Use the lobby-captured calibration if the player set one there;
                        // otherwise fall back to capturing the heading at the buzzer.
                        // If no orientation sample has arrived yet, defer the fallback
                        // capture until the first real reading (#69).
                        if (calibration == null && !pipelineStarted) {
                            when (
                                val decision = resolveCalibration(
                                    storedCal = calibrationStore.calibration.value,
                                    rawAzimuth = latestRawAzimuth,
                                )
                            ) {
                                is CalibrationDecision.UseNow -> {
                                    val cal = decision.calibration
                                    calibration = cal
                                    calibratedAim = cal.calibrate(latestRawAzimuth ?: 0f)
                                    pipelineStarted = true
                                    inputPipeline.start(
                                        scope = viewModelScope,
                                        playerId = myId,
                                        mode = mode,
                                        calibration = cal,
                                        onInput = session::submitLocalInput,
                                        onShieldArmProgress = { latestShieldArmProgress = it },
                                    )
                                }
                                CalibrationDecision.Defer -> {
                                    // No lobby calibration and no sensor sample yet —
                                    // defer capture until the first orientation reading.
                                    pendingCalibrationCapture = true
                                }
                            }
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
                        lastStatus = PlayerStatus.IDLE
                        calibration = null
                        pendingCalibrationCapture = false
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
        val myCell = myLobby?.seatCell ?: 0
        val myPos = Position(x = (myCell % GRID_COLUMNS).toFloat(), y = (myCell / GRID_COLUMNS).toFloat())
        val tolerance = if (mode == GameMode.KIDS) KidsRules.AIM_TOLERANCE_DEGREES else Bearing.DEFAULT_TOLERANCE_DEGREES

        val compassTargets = buildCompassTargets(snapshot, lobbyPlayers, myId, myPos, tolerance)
        val opponents = buildOpponents(snapshot, lobbyPlayers, myId)
        val warningActive = snapshot.players.any { it.id != myId && it.targetId == myId }

        val myElement = if (mode == GameMode.STANDARD) myLobby?.element?.name?.let { ELEMENT_EMOJIS[it] } else null
        val mySpriteEmoji = if (mode == GameMode.KIDS) myLobby?.spriteId?.let { SPRITE_EMOJIS.getOrNull(it) } else null

        val flash = computeFlash(snapshot.events, myId, mode)
        val existingFlash = (_uiState.value as? GameUiState.Playing)?.flashEvent
        val resolvedFlash = flash ?: existingFlash

        if (flash != null) {
            viewModelScope.launch {
                delay(FLASH_DURATION_MILLIS)
                val s = _uiState.value
                if (s is GameUiState.Playing && s.flashEvent == flash) {
                    _uiState.value = s.copy(flashEvent = null)
                }
            }
        }

        val myStatus = mySnap?.status ?: PlayerStatus.IDLE
        val resolvedEffect = resolveActionEffect(snapshot, myStatus, myId, mode, myLobby?.element)

        return GameUiState.Playing(
            mode = mode,
            myHp = mySnap?.hp ?: StandardRules.MAX_HP,
            myStars = mySnap?.stars ?: 0,
            myElement = myElement,
            mySpriteEmoji = mySpriteEmoji,
            myStatus = mySnap?.status?.name ?: PlayerStatus.IDLE.name,
            isEliminated = mySnap?.status == PlayerStatus.ELIMINATED,
            compassTargets = compassTargets,
            azimuthDegrees = latestRawAzimuth ?: 0f,
            remainingMillis = snapshot.remainingMillis,
            roundWins = lastKnownRoundWins,
            maxRoundWins = StandardRules.ROUNDS_TO_WIN,
            warningActive = warningActive,
            opponents = opponents,
            flashEvent = resolvedFlash,
            actionEffect = resolvedEffect,
            shielding = myStatus == PlayerStatus.SHIELDING,
            shieldArmProgress = latestShieldArmProgress,
            shieldRemainingFraction = shieldRemainingFraction(mySnap),
            restingUntilMillis = mySnap?.restingUntilMillis ?: 0L,
            debugAimDegrees = if (BuildConfig.DEBUG) calibratedAim else null,
        )
    }

    /** Remaining shield budget as a `[0, 1]` fraction of [StandardRules.SHIELD_BUDGET_MILLIS]. */
    private fun shieldRemainingFraction(snap: PlayerSnapshot?): Float =
        (snap?.shieldRemainingMillis ?: 0L).toFloat() / StandardRules.SHIELD_BUDGET_MILLIS

    private fun buildCompassTargets(
        snapshot: GameSnapshot,
        lobbyPlayers: List<LobbyPlayer>,
        myId: Int,
        myPos: Position,
        tolerance: Float,
    ): List<CompassTarget> = snapshot.players
        .filter { it.id != myId }
        .mapIndexedNotNull { index, playerSnap ->
            val opponentLobby = lobbyPlayers.firstOrNull { it.id == playerSnap.id } ?: return@mapIndexedNotNull null
            val cell = opponentLobby.seatCell ?: return@mapIndexedNotNull null
            val opponentPos = Position(x = (cell % GRID_COLUMNS).toFloat(), y = (cell / GRID_COLUMNS).toFloat())
            val bearing = Bearing.calculate(myPos, opponentPos)
            val onTarget = Bearing.isOnTarget(calibratedAim, bearing, tolerance)
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
     * Picks the one-shot [ActionEffectKind] for this snapshot, branching on mode.
     *
     * Impacts are driven by host [com.justb81.compassduel.net.protocol.GameEvent]s
     * (HIT / BLOCKED in Standard, CAUGHT / BUBBLED in Kids). The projectile is
     * fired on the local player's transition into [PlayerStatus.ATTACKING], which
     * the host already reports in every snapshot — no new payload is introduced.
     *
     * Kids Mode never returns [ActionEffectKind.DAMAGE_TAKEN]; incoming
     * sparkles read as friendly catches/bubbles only.
     */
    private fun computeActionEffectKind(
        events: List<com.justb81.compassduel.net.protocol.GameEvent>,
        myStatus: PlayerStatus,
        myId: Int,
        mode: GameMode,
    ): ActionEffectKind? {
        val justStartedAttacking = myStatus == PlayerStatus.ATTACKING && lastStatus != PlayerStatus.ATTACKING
        lastStatus = myStatus

        val fromEvents = events.firstNotNullOfOrNull { eventToEffectKind(it, myId, mode) }
        // Impacts win over the launch effect within the same snapshot.
        return fromEvents ?: if (justStartedAttacking) ActionEffectKind.PROJECTILE_FIRED else null
    }

    /**
     * Resolves the transient action effect for the current snapshot and schedules
     * it to clear after [ACTION_EFFECT_DURATION_MILLIS]; falls back to the effect
     * already showing when no new one fires. [rawElement] themes Standard-Mode
     * effects and is ignored in Kids Mode.
     */
    private fun resolveActionEffect(
        snapshot: GameSnapshot,
        myStatus: PlayerStatus,
        myId: Int,
        mode: GameMode,
        rawElement: Element?,
    ): ActionEffect? {
        val effectKind = computeActionEffectKind(snapshot.events, myStatus, myId, mode)
        val element = if (mode == GameMode.STANDARD) rawElement else null
        val newEffect = effectKind?.let { kind ->
            actionEffectTrigger += 1
            ActionEffect(kind = kind, element = element, triggerId = actionEffectTrigger)
        }
        if (newEffect != null) {
            viewModelScope.launch {
                delay(ACTION_EFFECT_DURATION_MILLIS)
                val s = _uiState.value
                if (s is GameUiState.Playing && s.actionEffect == newEffect) {
                    _uiState.value = s.copy(actionEffect = null)
                }
            }
        }
        return newEffect ?: (_uiState.value as? GameUiState.Playing)?.actionEffect
    }

    /** Maps a single host [GameEvent] to the effect it should play for the local player, or null. */
    private fun eventToEffectKind(
        event: com.justb81.compassduel.net.protocol.GameEvent,
        myId: Int,
        mode: GameMode,
    ): ActionEffectKind? = when {
        mode == GameMode.STANDARD && event.type == GameEventType.HIT && event.actorId == myId ->
            ActionEffectKind.IMPACT_LANDED
        mode == GameMode.STANDARD && event.type == GameEventType.BLOCKED && event.actorId == myId ->
            ActionEffectKind.IMPACT_BLOCKED
        mode == GameMode.STANDARD && event.type == GameEventType.HIT && event.targetId == myId ->
            ActionEffectKind.DAMAGE_TAKEN
        mode == GameMode.KIDS && event.type == GameEventType.CAUGHT && event.actorId == myId ->
            ActionEffectKind.IMPACT_LANDED
        mode == GameMode.KIDS && event.type == GameEventType.BUBBLED && event.targetId == myId ->
            ActionEffectKind.IMPACT_BLOCKED
        else -> null
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private fun stopPipeline() {
        if (pipelineStarted) {
            inputPipeline.stop()
            pipelineStarted = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPipeline()
    }

    companion object {
        private const val INITIAL_COUNTDOWN_SECONDS = 3
        private const val MILLIS_PER_SECOND = 1_000L
        private const val GRID_COLUMNS = 3
        private const val FLASH_DURATION_MILLIS = 500L
        private const val ACTION_EFFECT_DURATION_MILLIS = 500L

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

        /**
         * Resolves which [AimCalibration] to use when transitioning to PLAYING, or
         * signals that the capture must be deferred (#69).
         *
         * Decision rules:
         * 1. If a lobby-captured calibration exists ([storedCal] != null), use it
         *    immediately — no sensor sample is needed.
         * 2. If no lobby calibration exists but a real sensor reading is available
         *    ([rawAzimuth] != null), create a new calibration from that reading.
         * 3. If neither is available, return [CalibrationDecision.Defer] so the
         *    caller can set a flag and retry on the first real orientation sample.
         *
         * @param storedCal The lobby-captured calibration from [AimCalibrationStore], or null.
         * @param rawAzimuth The latest raw azimuth from [OrientationSensor], or null when
         *   no sample has been received yet.
         * @return [CalibrationDecision.UseNow] with the resolved calibration, or
         *   [CalibrationDecision.Defer] when the capture must wait for a real reading.
         */
        internal fun resolveCalibration(
            storedCal: AimCalibration?,
            rawAzimuth: Float?,
        ): CalibrationDecision = when {
            storedCal != null -> CalibrationDecision.UseNow(storedCal)
            rawAzimuth != null -> CalibrationDecision.UseNow(AimCalibration(rawAzimuth))
            else -> CalibrationDecision.Defer
        }
    }

    /** Result of [resolveCalibration]. */
    internal sealed interface CalibrationDecision {
        /** Use [calibration] immediately — pipeline can start now. */
        data class UseNow(val calibration: AimCalibration) : CalibrationDecision

        /** No real sensor reading available yet — defer until first orientation sample. */
        data object Defer : CalibrationDecision
    }
}
