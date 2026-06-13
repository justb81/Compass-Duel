package com.justb81.compassduel.session

import com.justb81.compassduel.di.ApplicationScope
import com.justb81.compassduel.game.Position
import com.justb81.compassduel.game.engine.EnginePlayerSetup
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.game.engine.KidsRuleSet
import com.justb81.compassduel.game.engine.ModeRuleSet
import com.justb81.compassduel.game.engine.RoundOutcome
import com.justb81.compassduel.game.engine.StandardRuleSet
import com.justb81.compassduel.game.standard.MatchScore
import com.justb81.compassduel.net.ConnectionEvent
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.TransportError
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.GameSnapshot
import com.justb81.compassduel.net.protocol.LobbyPlayer
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.net.protocol.PlayerAction
import com.justb81.compassduel.net.protocol.RoundPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The role this device is playing in the current session. */
enum class SessionRole { HOST, CLIENT }

/**
 * Navigation-level events emitted by [GameSession] when significant state transitions occur.
 *
 * Screens collect [GameSession.sessionEvents] and drive navigation accordingly.
 */
sealed interface SessionEvent {

    /** The host started a round — navigate to the game screen. */
    data object RoundStarted : SessionEvent

    /** The match has ended — navigate to the results screen. */
    data object MatchOver : SessionEvent

    /** The host has initiated a rematch — return to the lobby screen. */
    data object RematchRequested : SessionEvent

    /** A peer was lost (host disconnected on client; required client disconnected on host mid-round). */
    data object PeerLost : SessionEvent
}

/**
 * Factory for [GameEngine] instances; injected so tests can supply a fake engine.
 *
 * The real binding in [com.justb81.compassduel.di.AppModule] delegates to the
 * three-argument [GameEngine] constructor.
 */
fun interface GameEngineFactory {
    fun create(rules: ModeRuleSet, clock: GameClock, scope: CoroutineScope): GameEngine
}

/**
 * Role-agnostic game session facade consumed by all ViewModels.
 *
 * On the **host**: drives the [GameEngine], broadcasts [NetMessage.StateBroadcast] snapshots to
 * clients, handles lobby management and round sequencing (best of 3 for standard mode).
 *
 * On the **client**: decodes host messages into the same [StateFlow] surface so ViewModels
 * need no role-specific logic.
 *
 * ### Seat-position grid
 * Cells are numbered 0–8 in reading order (left-to-right, top-to-bottom).
 * `Position(x = cell % 3, y = cell / 3)` places row 0 at the front of the play area
 * (closest to opposing players) and row 2 at the back.
 *
 * ### Match flow (standard)
 * Host runs best-of-[com.justb81.compassduel.game.standard.StandardRules.ROUNDS_TO_WIN × 2 − 1].
 * When a round ends without a match winner the host waits [NEXT_ROUND_DELAY_MILLIS] then starts
 * the next round automatically. Kids mode plays a single round.
 *
 */
@Singleton
class GameSession @Inject constructor(
    private val transport: MessageTransport,
    private val clock: GameClock,
    private val engineFactory: GameEngineFactory,
    @ApplicationScope private val scope: CoroutineScope,
) {

    // ---------------------------------------------------------------------------
    // Public state
    // ---------------------------------------------------------------------------

    private val _role = MutableStateFlow<SessionRole?>(null)

    /** The role this device is currently playing, or null when not in a session. */
    val role: StateFlow<SessionRole?> = _role.asStateFlow()

    private val _lobby = MutableStateFlow<NetMessage.LobbyState?>(null)

    /** Current lobby state, or null when not in a lobby. */
    val lobby: StateFlow<NetMessage.LobbyState?> = _lobby.asStateFlow()

    private val _snapshot = MutableStateFlow<GameSnapshot?>(null)

    /** Latest authoritative game snapshot, or null before the first round starts. */
    val snapshot: StateFlow<GameSnapshot?> = _snapshot.asStateFlow()

    private val _roundEnd = MutableStateFlow<NetMessage.RoundEnd?>(null)

    /** Round-end result, or null while the round is in progress. */
    val roundEnd: StateFlow<NetMessage.RoundEnd?> = _roundEnd.asStateFlow()

    // replay=1: a late or re-subscribing collector (e.g. after recomposition tears down
    // LaunchedEffect) receives the most recent navigation event so navigation is never
    // permanently stranded by a dropped event (#63).
    private val _sessionEvents = MutableSharedFlow<SessionEvent>(
        replay = 1,
        extraBufferCapacity = SESSION_BUFFER,
    )

    /** Navigation-level events that drive screen transitions. */
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    /**
     * Endpoints discovered during [joinLobby]; backed directly by the transport.
     * Clients observe this to populate the "nearby hosts" list.
     */
    val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = transport.discoveredEndpoints

    /**
     * Transport failures (e.g. Play Services threw while starting advertising/discovery);
     * backed directly by the transport. ViewModels observe this to surface a user-facing error.
     */
    val transportErrors: SharedFlow<TransportError> = transport.transportErrors

    // ---------------------------------------------------------------------------
    // Private session state (host only unless noted)
    // ---------------------------------------------------------------------------

    private var localPlayerName: String = ""
    private var hostEndpointId: String? = null
    private var clientPlayerName: String = ""
    private var lobbyPlayers: MutableList<LobbyPlayer> = mutableListOf()
    private val endpointToPlayerId: MutableMap<String, Int> = mutableMapOf()
    private var engine: GameEngine? = null
    private var snapshotJob: Job? = null
    private var messageJob: Job? = null
    private var connectionJob: Job? = null
    /**
     * Tracks the coroutine that awaits [GameEngine.roundOverSignal] so it can be
     * cancelled on reset/rematch, preventing a dangling await on a never-completing
     * deferred when the engine is stopped mid-round (e.g. disconnect, #66).
     */
    private var roundOverWaitJob: Job? = null
    /**
     * Tracks the coroutine launched by [computeAndBroadcastRoundEnd] that waits for
     * [ROUND_END_DELAY_MILLIS] (and possibly [NEXT_ROUND_DELAY_MILLIS]) before advancing
     * the match. Stored so it can be cancelled on reset/rematch (#62).
     */
    private var roundAdvanceJob: Job? = null
    private var roundIndex: Int = 0
    private var matchScore: MatchScore = MatchScore()
    private var matchInProgress: Boolean = false

    // ---------------------------------------------------------------------------
    // Host API
    // ---------------------------------------------------------------------------

    /**
     * Starts hosting a lobby. Advertises over Nearby so clients can discover this device.
     *
     * The host is always player id [HOST_PLAYER_ID]. [mode] can be changed later via [setMode].
     *
     * @param playerName The host's display name.
     * @param mode The initial game mode.
     */
    fun hostLobby(playerName: String, mode: GameMode) {
        reset()
        _role.value = SessionRole.HOST
        localPlayerName = playerName
        lobbyPlayers = mutableListOf(LobbyPlayer(id = HOST_PLAYER_ID, name = playerName))
        broadcastLobbyToAll(mode)
        startConnectionListener()
        startMessageListener()
        transport.startAdvertising(playerName)
    }

    /**
     * Changes the game mode (host only). Rebroadcasts lobby state to all clients.
     *
     * @param mode The new game mode.
     */
    fun setMode(mode: GameMode) {
        broadcastLobbyToAll(mode)
    }

    /**
     * Validates the lobby and starts the match (host only).
     *
     * Requirements: 2–4 players; all players have chosen a unique seat;
     * in STANDARD mode all players have chosen a unique element.
     *
     * @throws IllegalStateException if validation fails.
     */
    fun startMatch() {
        val currentLobby = _lobby.value ?: return
        val players = lobbyPlayers.toList()

        check(players.size in MIN_PLAYERS..MAX_PLAYERS) {
            "Need $MIN_PLAYERS–$MAX_PLAYERS players; have ${players.size}"
        }
        check(players.all { it.seatCell != null }) { "All players must choose a seat" }
        val seats = players.mapNotNull { it.seatCell }
        check(seats.size == seats.toSet().size) { "Duplicate seat assignments" }

        if (currentLobby.mode == GameMode.STANDARD) {
            check(players.all { it.element != null }) { "All players must choose an element in Standard mode" }
            val elements = players.mapNotNull { it.element }
            check(elements.size == elements.toSet().size) { "Duplicate element choices" }
        }

        roundIndex = 0
        matchScore = MatchScore()
        matchInProgress = true
        startRoundInternal(currentLobby.mode, players)
        transport.acceptNewConnections = false
    }

    /**
     * Initiates a rematch (host only). Broadcasts [NetMessage.Rematch], resets to lobby,
     * and emits [SessionEvent.RematchRequested].
     */
    fun requestRematch() {
        // Cancel any pending round-advance coroutine before resetting match state (#62).
        roundAdvanceJob?.cancel()
        roundAdvanceJob = null
        // Cancel the roundOverSignal awaiter so a late-completing signal cannot
        // trigger a spurious computeAndBroadcastRoundEnd after the rematch resets
        // all state (#66 / #62).
        roundOverWaitJob?.cancel()
        roundOverWaitJob = null
        transport.broadcast(NetMessage.Rematch)
        val currentLobby = _lobby.value ?: return
        transport.acceptNewConnections = true
        matchInProgress = false
        roundIndex = 0
        matchScore = MatchScore()
        _snapshot.value = null
        _roundEnd.value = null
        broadcastLobbyToAll(currentLobby.mode)
        _sessionEvents.tryEmit(SessionEvent.RematchRequested)
    }

    // ---------------------------------------------------------------------------
    // Client API
    // ---------------------------------------------------------------------------

    /**
     * Starts discovering hosts over Nearby (client only).
     *
     * @param playerName The client's display name, sent to the host after connecting.
     */
    fun joinLobby(playerName: String) {
        reset()
        _role.value = SessionRole.CLIENT
        clientPlayerName = playerName
        startConnectionListener()
        startMessageListener()
        transport.startDiscovery()
    }

    /**
     * Requests a connection to a discovered host endpoint (client only).
     *
     * @param endpointId The endpoint to connect to (from [MessageTransport.discoveredEndpoints]).
     */
    fun connectTo(endpointId: String) {
        hostEndpointId = endpointId
        transport.requestConnection(endpointId, clientPlayerName)
        transport.stopDiscovery()
    }

    // ---------------------------------------------------------------------------
    // Both roles
    // ---------------------------------------------------------------------------

    /**
     * Selects a seat on the 3×3 grid.
     *
     * Host: mutates the local lobby and rebroadcasts. Client: sends [NetMessage.SeatChosen].
     *
     * @param cell Seat cell index in [0, 8].
     */
    fun chooseSeat(cell: Int) {
        when (_role.value) {
            SessionRole.HOST -> {
                val mode = _lobby.value?.mode ?: GameMode.STANDARD
                updateHostPlayer { it.copy(seatCell = cell) }
                broadcastLobbyToAll(mode)
            }
            SessionRole.CLIENT -> transport.send(
                hostEndpointId ?: return,
                NetMessage.SeatChosen(cell),
            )
            null -> Unit
        }
    }

    /**
     * Selects a character (element or sprite).
     *
     * Host: mutates the local lobby and rebroadcasts. Client: sends [NetMessage.CharacterChosen].
     *
     * @param element Chosen element for Standard Mode, or null.
     * @param spriteId Chosen sprite index for Kids Mode, or null.
     */
    fun chooseCharacter(
        element: com.justb81.compassduel.game.Element? = null,
        spriteId: Int? = null,
    ) {
        when (_role.value) {
            SessionRole.HOST -> {
                val mode = _lobby.value?.mode ?: GameMode.STANDARD
                updateHostPlayer { p ->
                    p.copy(
                        element = element,
                        spriteId = spriteId,
                        ready = element != null || spriteId != null,
                    )
                }
                broadcastLobbyToAll(mode)
            }
            SessionRole.CLIENT -> transport.send(
                hostEndpointId ?: return,
                NetMessage.CharacterChosen(element = element, spriteId = spriteId),
            )
            null -> Unit
        }
    }

    /**
     * Submits a player input.
     *
     * Host: feeds directly into the engine. Client: sends to the host endpoint.
     *
     * @param input The player's current aim and action.
     */
    fun submitLocalInput(input: NetMessage.PlayerInput) {
        when (_role.value) {
            SessionRole.HOST -> feedInputToEngine(input, HOST_PLAYER_ID)
            SessionRole.CLIENT -> transport.send(hostEndpointId ?: return, input)
            null -> Unit
        }
    }

    /**
     * Leaves the session, stopping all transport activity and resetting all state flows.
     */
    fun leave() {
        transport.stopAll()
        reset()
    }

    // ---------------------------------------------------------------------------
    // Internal — message handling
    // ---------------------------------------------------------------------------

    private fun startMessageListener() {
        messageJob?.cancel()
        messageJob = scope.launch {
            transport.incomingMessages.collect { (endpointId, message) ->
                handleIncomingMessage(endpointId, message)
            }
        }
    }

    private fun startConnectionListener() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            transport.connectionEvents.collect { event ->
                handleConnectionEvent(event)
            }
        }
    }

    private fun handleConnectionEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Connected -> {
                if (_role.value == SessionRole.CLIENT && event.endpointId == hostEndpointId) {
                    transport.send(event.endpointId, NetMessage.ClientHello(clientPlayerName))
                }
            }
            is ConnectionEvent.Disconnected -> handleDisconnection(event.endpointId)
        }
    }

    private fun handleDisconnection(endpointId: String) {
        when (_role.value) {
            SessionRole.HOST -> {
                val playerId = endpointToPlayerId.remove(endpointId) ?: return
                if (matchInProgress) {
                    engine?.stop()
                    _sessionEvents.tryEmit(SessionEvent.PeerLost)
                } else {
                    lobbyPlayers.removeAll { it.id == playerId }
                    broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
                }
            }
            SessionRole.CLIENT -> {
                if (endpointId == hostEndpointId) {
                    _sessionEvents.tryEmit(SessionEvent.PeerLost)
                    reset()
                }
            }
            null -> Unit
        }
    }

    private fun handleIncomingMessage(endpointId: String, message: NetMessage) {
        when (_role.value) {
            SessionRole.HOST -> handleHostIncoming(endpointId, message)
            SessionRole.CLIENT -> handleClientIncoming(endpointId, message)
            null -> Unit
        }
    }

    private fun handleHostIncoming(endpointId: String, message: NetMessage) {
        when (message) {
            // Messages only valid in the host→client direction — reject from clients.
            is NetMessage.LobbyState,
            is NetMessage.RoundStart,
            is NetMessage.StateBroadcast,
            is NetMessage.RoundEnd,
            is NetMessage.Rematch,
            -> return
            is NetMessage.ClientHello -> {
                // Cap lobby size: reject late joiners.
                if (lobbyPlayers.size >= MAX_PLAYERS) return
                // Truncate name to prevent unbounded data reaching game logic.
                val name = message.playerName.take(MAX_PLAYER_NAME_LENGTH)
                // Allocate lowest unused id (ids start at HOST_PLAYER_ID + 1 = 2).
                val newId = (HOST_PLAYER_ID + 1..MAX_PLAYERS + 1).first { candidate ->
                    lobbyPlayers.none { it.id == candidate }
                }
                endpointToPlayerId[endpointId] = newId
                lobbyPlayers.add(LobbyPlayer(id = newId, name = name))
                broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
            }
            is NetMessage.SeatChosen -> {
                // Reject out-of-range cell indices.
                if (message.cell !in 0..8) return
                // Reject already-taken seats.
                if (lobbyPlayers.any { it.seatCell == message.cell }) return
                val playerId = endpointToPlayerId[endpointId] ?: return
                updatePlayer(playerId) { it.copy(seatCell = message.cell) }
                broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
            }
            is NetMessage.CharacterChosen -> {
                val playerId = endpointToPlayerId[endpointId] ?: return
                updatePlayer(playerId) { p ->
                    p.copy(
                        element = message.element,
                        spriteId = message.spriteId,
                        ready = message.element != null || message.spriteId != null,
                    )
                }
                broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
            }
            is NetMessage.PlayerInput -> {
                // Reject inputs from endpoints not registered in the lobby.
                val trustedId = endpointToPlayerId[endpointId] ?: return
                // Reject non-finite sensor values before they reach game logic.
                if (!message.aimDegrees.isFinite()) return
                if (!message.pitchDegrees.isFinite()) return
                feedInputToEngine(message, trustedId)
            }
        }
    }

    private fun handleClientIncoming(endpointId: String, message: NetMessage) {
        // Only accept messages from the known host endpoint.
        if (endpointId != hostEndpointId) return
        when (message) {
            is NetMessage.LobbyState -> _lobby.value = message
            is NetMessage.RoundStart -> {
                // Symmetric with the host: host clears _roundEnd before broadcasting
                // RoundStart; client clears it here upon receipt — both roles see the
                // same "no stale roundEnd during the new round" invariant (#68).
                _roundEnd.value = null
                _snapshot.value = null
                _sessionEvents.tryEmit(SessionEvent.RoundStarted)
            }
            is NetMessage.StateBroadcast -> _snapshot.value = message.snapshot
            is NetMessage.RoundEnd -> {
                _roundEnd.value = message
                if (message.matchWinnerId != null || message.kidsAwards != null) {
                    _sessionEvents.tryEmit(SessionEvent.MatchOver)
                }
                // Non-final round: _roundEnd remains set (brief display window) until the
                // next RoundStart arrives and clears it — matching the host's own display
                // window (#68).
            }
            is NetMessage.Rematch -> {
                _roundEnd.value = null
                _snapshot.value = null
                _sessionEvents.tryEmit(SessionEvent.RematchRequested)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------
    // Internal — engine management (host only)
    // ---------------------------------------------------------------------------

    private fun startRoundInternal(mode: GameMode, players: List<LobbyPlayer>) {
        val rules: ModeRuleSet = when (mode) {
            GameMode.STANDARD -> StandardRuleSet()
            GameMode.KIDS -> KidsRuleSet()
        }

        val roundStart = NetMessage.RoundStart(
            mode = mode,
            roundIndex = roundIndex,
            roundDurationSeconds = rules.roundDurationSeconds,
            players = players,
            facingCaptureSeconds = FACING_CAPTURE_SECONDS,
        )
        transport.broadcast(roundStart)
        _sessionEvents.tryEmit(SessionEvent.RoundStarted)

        val engineSetup = players.map { p ->
            val cell = p.seatCell ?: 0
            EnginePlayerSetup(
                id = p.id,
                name = p.name,
                position = Position(
                    x = (cell % GRID_COLUMNS).toFloat(),
                    y = (cell / GRID_COLUMNS).toFloat(),
                ),
                element = p.element,
                spriteId = p.spriteId,
            )
        }

        val activeEngine = engineFactory.create(rules, clock, scope)
        engine = activeEngine
        activeEngine.startRound(engineSetup, roundIndex)

        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            // Forward all snapshots to clients. Snapshot collection only; round-end
            // computation is triggered by roundOverSignal below (#66).
            activeEngine.snapshots.collect { snap ->
                _snapshot.value = snap
                transport.broadcast(NetMessage.StateBroadcast(snap))
            }
        }

        // Await the engine's terminal completion signal rather than observing a
        // ROUND_OVER snapshot inside the tick loop. This is race-free: the signal
        // is completed atomically inside transitionToRoundOver() before the snapshot
        // is emitted, so even if the tick loop is cancelled at the boundary the
        // round-end broadcast still fires (#66).
        // The job is stored so reset()/requestRematch() can cancel it, preventing a
        // dangling await when the engine is stopped mid-round (e.g. peer disconnect).
        roundOverWaitJob?.cancel()
        roundOverWaitJob = scope.launch {
            activeEngine.roundOverSignal.await()
            computeAndBroadcastRoundEnd(mode)
        }
    }

    private fun computeAndBroadcastRoundEnd(mode: GameMode) {
        // Store in roundAdvanceJob so reset() and requestRematch() can cancel it (#62).
        roundAdvanceJob = scope.launch {
            delay(ROUND_END_DELAY_MILLIS)

            val roundEnd = buildRoundEnd(mode) ?: return@launch
            _roundEnd.value = roundEnd
            transport.broadcast(roundEnd)

            when (mode) {
                GameMode.STANDARD -> {
                    if (roundEnd.matchWinnerId != null) {
                        _sessionEvents.tryEmit(SessionEvent.MatchOver)
                    } else {
                        delay(NEXT_ROUND_DELAY_MILLIS)
                        roundIndex++
                        // Clear between-round results on both host and client at the same
                        // lifecycle point (before RoundStart is broadcast) so both roles
                        // display the same "stale roundEnd" window (#68).
                        _roundEnd.value = null
                        val currentLobby = _lobby.value ?: return@launch
                        startRoundInternal(currentLobby.mode, lobbyPlayers.toList())
                    }
                }
                GameMode.KIDS -> _sessionEvents.tryEmit(SessionEvent.MatchOver)
            }
        }
    }

    private fun buildRoundEnd(mode: GameMode): NetMessage.RoundEnd? {
        val outcome = engine?.roundOutcome() ?: return null
        return when (mode) {
            GameMode.STANDARD -> buildStandardRoundEnd(outcome)
            GameMode.KIDS -> buildKidsRoundEnd(outcome)
        }
    }

    private fun buildStandardRoundEnd(outcome: RoundOutcome): NetMessage.RoundEnd {
        val winnerId = (outcome as? RoundOutcome.StandardWinner)?.winnerId
        matchScore = matchScore.recordRoundWin(winnerId)
        return NetMessage.RoundEnd(
            roundWinnerId = winnerId,
            matchScore = matchScore.roundWins,
            matchWinnerId = matchScore.matchWinnerId(),
        )
    }

    /**
     * Builds a Kids Mode [NetMessage.RoundEnd] from the engine's authoritative
     * [RoundOutcome.KidsOutcome], which carries full per-player stats
     * (including [com.justb81.compassduel.game.kids.KidsRoundStats.bubbleBlocks]
     * and [com.justb81.compassduel.game.kids.KidsRoundStats.sparklesThrown]) accumulated
     * by [com.justb81.compassduel.game.engine.KidsRuleSet] during the round.
     */
    private fun buildKidsRoundEnd(outcome: RoundOutcome): NetMessage.RoundEnd {
        val kidsOutcome = outcome as? RoundOutcome.KidsOutcome ?: return NetMessage.RoundEnd()
        val statsList = kidsOutcome.stats.values.toList()
        return NetMessage.RoundEnd(kidsAwards = kidsOutcome.awards, kidsStats = statsList)
    }

    private fun feedInputToEngine(input: NetMessage.PlayerInput, trustedPlayerId: Int) {
        val isShielding = input.action == PlayerAction.SHIELD
        val action = when (input.action) {
            PlayerAction.ATTACK, PlayerAction.DODGE -> input.action
            else -> null
        }
        engine?.submitInput(
            playerId = trustedPlayerId,
            aimDegrees = input.aimDegrees,
            isShielding = isShielding,
            action = action,
        )
    }

    // ---------------------------------------------------------------------------
    // Internal — lobby helpers (host only)
    // ---------------------------------------------------------------------------

    private fun broadcastLobbyToAll(mode: GameMode) {
        val players = lobbyPlayers.toList()
        _lobby.value = NetMessage.LobbyState(mode = mode, players = players, yourPlayerId = HOST_PLAYER_ID)
        endpointToPlayerId.forEach { (endpointId, playerId) ->
            transport.send(
                endpointId,
                NetMessage.LobbyState(mode = mode, players = players, yourPlayerId = playerId),
            )
        }
    }

    private fun updateHostPlayer(transform: (LobbyPlayer) -> LobbyPlayer) {
        val idx = lobbyPlayers.indexOfFirst { it.id == HOST_PLAYER_ID }
        if (idx >= 0) lobbyPlayers[idx] = transform(lobbyPlayers[idx])
    }

    private fun updatePlayer(playerId: Int, transform: (LobbyPlayer) -> LobbyPlayer) {
        val idx = lobbyPlayers.indexOfFirst { it.id == playerId }
        if (idx >= 0) lobbyPlayers[idx] = transform(lobbyPlayers[idx])
    }

    // ---------------------------------------------------------------------------
    // Internal — reset
    // ---------------------------------------------------------------------------

    private fun reset() {
        // Cancel the round-advance coroutine first so it cannot race a new match (#62).
        roundAdvanceJob?.cancel()
        roundAdvanceJob = null
        // Cancel the roundOverSignal awaiter so it cannot fire against a reset/rematched
        // session when the engine was stopped mid-round (e.g. peer disconnect, #66).
        roundOverWaitJob?.cancel()
        roundOverWaitJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        messageJob?.cancel()
        messageJob = null
        connectionJob?.cancel()
        connectionJob = null
        engine?.stop()
        engine = null
        _role.value = null
        _lobby.value = null
        _snapshot.value = null
        _roundEnd.value = null
        lobbyPlayers.clear()
        endpointToPlayerId.clear()
        localPlayerName = ""
        clientPlayerName = ""
        hostEndpointId = null
        roundIndex = 0
        matchScore = MatchScore()
        matchInProgress = false
        transport.acceptNewConnections = true
    }

    companion object {
        private const val HOST_PLAYER_ID = 1
        private const val MIN_PLAYERS = 2
        private const val MAX_PLAYERS = 4
        private const val GRID_COLUMNS = 3
        private const val MAX_PLAYER_NAME_LENGTH = 24

        /** Facing-capture window at round start (seconds). */
        private const val FACING_CAPTURE_SECONDS = 3

        /** Buffer for session-level navigation events. */
        private const val SESSION_BUFFER = 16

        /** Delay (ms) between the ROUND_OVER snapshot and broadcasting RoundEnd. */
        private const val ROUND_END_DELAY_MILLIS = 200L

        /** Delay (ms) between RoundEnd and auto-starting the next round in standard mode. */
        private const val NEXT_ROUND_DELAY_MILLIS = 3_000L
    }
}
