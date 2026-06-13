package com.justb81.compassduel.session

import com.justb81.compassduel.di.ApplicationScope
import com.justb81.compassduel.di.GameLoopDispatcher
import com.justb81.compassduel.game.MovementPolicy
import com.justb81.compassduel.game.MovementVerdict
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
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * A player forfeited mid-match by leaving their seat; the host needs everyone to
     * re-greet before the next round. Screens return to the lobby greeting view.
     */
    data object RegreetRequired : SessionEvent
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
 * ### Greeting bearings
 * Players establish their relative bearings via the "bow to greet" handshake instead of a
 * seat grid: each player bows at every opponent, capturing the absolute azimuth from their
 * phone toward that opponent. The host stores these as [bearingMatrix] (`actorId →
 * (targetId → degrees)`) and feeds them to the engine for hit detection.
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
    @GameLoopDispatcher private val gameLoopDispatcher: CoroutineDispatcher,
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

    private val _myBearings = MutableStateFlow<Map<Int, Float>>(emptyMap())

    /** The local player's captured outgoing bearings (`targetId → degrees`) for the round. */
    val myBearings: StateFlow<Map<Int, Float>> = _myBearings.asStateFlow()

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

    // lobbyPlayers is guarded by lobbyLock: it is mutated both from the transport-collector
    // coroutines (Dispatchers.Default pool) and from main-thread API calls (hostLobby,
    // submitBow, chooseCharacter, startMatch). All read-modify-write sequences on
    // lobbyPlayers must be performed inside synchronized(lobbyLock) (#61).
    //
    // endpointToPlayerId is a ConcurrentHashMap so its individual operations (put/remove/get)
    // are atomic. Compound sequences that read AND mutate it (e.g. allocating a new id) are
    // also protected by lobbyLock so they remain consistent with lobbyPlayers mutations (#61).
    private val lobbyLock = Any()
    private var lobbyPlayers: MutableList<LobbyPlayer> = mutableListOf()
    private val endpointToPlayerId: MutableMap<String, Int> = ConcurrentHashMap()

    // Greeting bearing matrix: actorId -> (targetId -> absolute bearing degrees).
    // Mutated from transport-collector coroutines and main-thread API calls; guarded by
    // lobbyLock like lobbyPlayers (#61).
    private val bearingMatrix: MutableMap<Int, MutableMap<Int, Float>> = mutableMapOf()

    // Per-player accumulated step count since their bearings were last established; reset
    // when a player (re-)greets. Used by the movement-forfeit policy during a round.
    private val movementSteps: MutableMap<Int, Int> = ConcurrentHashMap()

    // Pending standard-mode next round deferred until forfeited players re-greet.
    private var awaitingRegreet: Boolean = false
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
        synchronized(lobbyLock) {
            lobbyPlayers = mutableListOf(LobbyPlayer(id = HOST_PLAYER_ID, name = playerName))
        }
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
     * Requirements: 2–4 players; every ordered pair of players has greeted each other
     * (a complete bearing matrix); in STANDARD mode all players have chosen a unique element.
     *
     * @throws IllegalStateException if validation fails.
     */
    fun startMatch() {
        val currentLobby = _lobby.value ?: return
        val players = synchronized(lobbyLock) { lobbyPlayers.toList() }

        check(players.size in MIN_PLAYERS..MAX_PLAYERS) {
            "Need $MIN_PLAYERS–$MAX_PLAYERS players; have ${players.size}"
        }
        check(synchronized(lobbyLock) { allPairsGreeted(players) }) {
            "All players must greet each other before starting"
        }

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
     * Records a bow at [toPlayerId], capturing the absolute [bearingDegrees] from the local
     * player toward that opponent during the greeting handshake.
     *
     * Host: records into [bearingMatrix] and rebroadcasts. Client: sends [NetMessage.Greeting].
     *
     * @param toPlayerId The greeted player's id.
     * @param bearingDegrees Raw absolute azimuth in `[0, 360)` captured at bow onset.
     */
    fun submitBow(toPlayerId: Int, bearingDegrees: Float) {
        if (!bearingDegrees.isFinite()) return
        when (_role.value) {
            SessionRole.HOST -> recordGreeting(HOST_PLAYER_ID, toPlayerId, bearingDegrees)
            SessionRole.CLIENT -> transport.send(
                hostEndpointId ?: return,
                NetMessage.Greeting(
                    fromPlayerId = _lobby.value?.yourPlayerId ?: return,
                    toPlayerId = toPlayerId,
                    bearingDegrees = bearingDegrees,
                ),
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
     * Reports locally detected physical movement (step detector / significant-motion).
     *
     * Host: evaluates the movement policy directly. Client: forwards to the host.
     *
     * @param stepDelta Steps detected since the previous report.
     * @param significant True when a significant-motion event was reported.
     */
    fun submitLocalMovement(stepDelta: Int, significant: Boolean) {
        when (_role.value) {
            SessionRole.HOST -> processMovement(HOST_PLAYER_ID, stepDelta, significant)
            SessionRole.CLIENT -> transport.send(
                hostEndpointId ?: return,
                NetMessage.PlayerMoved(_lobby.value?.yourPlayerId ?: return, stepDelta, significant),
            )
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
                    synchronized(lobbyLock) {
                        lobbyPlayers.removeAll { it.id == playerId }
                        bearingMatrix.remove(playerId)
                        bearingMatrix.values.forEach { it.remove(playerId) }
                    }
                    movementSteps.remove(playerId)
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
            is NetMessage.Regreet,
            -> Unit
            is NetMessage.ClientHello -> handleClientHello(endpointId, message)
            is NetMessage.Greeting -> handleGreeting(endpointId, message)
            is NetMessage.PlayerMoved -> handlePlayerMoved(endpointId, message)
            is NetMessage.CharacterChosen -> handleCharacterChosen(endpointId, message)
            is NetMessage.PlayerInput -> handlePlayerInput(endpointId, message)
        }
    }

    private fun handleClientHello(endpointId: String, message: NetMessage.ClientHello) {
        // Truncate name to prevent unbounded data reaching game logic.
        val name = message.playerName.take(MAX_PLAYER_NAME_LENGTH)
        // The compound size-check → id-allocation → add must be atomic under lobbyLock
        // so concurrent ClientHello messages cannot both pass the size guard (#61).
        val added = synchronized(lobbyLock) {
            // Cap lobby size: reject late joiners.
            if (lobbyPlayers.size >= MAX_PLAYERS) return@synchronized false
            // Allocate lowest unused id (ids start at HOST_PLAYER_ID + 1 = 2).
            val newId = (HOST_PLAYER_ID + 1..MAX_PLAYERS + 1).first { candidate ->
                lobbyPlayers.none { it.id == candidate }
            }
            endpointToPlayerId[endpointId] = newId
            lobbyPlayers.add(LobbyPlayer(id = newId, name = name))
            true
        }
        if (added) broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
    }

    private fun handleGreeting(endpointId: String, message: NetMessage.Greeting) {
        // Reject non-finite/out-of-range bearings before they reach game logic.
        if (!message.bearingDegrees.isFinite()) return
        if (message.bearingDegrees < 0f || message.bearingDegrees >= FULL_CIRCLE) return
        // Trust the endpoint→playerId mapping over the client-supplied fromPlayerId.
        val fromId = endpointToPlayerId[endpointId] ?: return
        recordGreeting(fromId, message.toPlayerId, message.bearingDegrees)
    }

    /**
     * Records [fromId] → [toId] = [bearingDegrees] in [bearingMatrix] (host-side), resets the
     * greeter's movement budget, and rebroadcasts. If a deferred next round was awaiting this
     * greeting, starts it once every pair is greeted again.
     */
    private fun recordGreeting(fromId: Int, toId: Int, bearingDegrees: Float) {
        val accepted = synchronized(lobbyLock) {
            // Reject greetings toward players not in the lobby (or self).
            if (fromId == toId || lobbyPlayers.none { it.id == toId }) return@synchronized false
            bearingMatrix.getOrPut(fromId) { mutableMapOf() }[toId] = bearingDegrees
            true
        }
        if (!accepted) return
        movementSteps[fromId] = 0
        if (awaitingRegreet) {
            maybeResumeAfterRegreet()
        } else {
            broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
        }
    }

    private fun handlePlayerMoved(endpointId: String, message: NetMessage.PlayerMoved) {
        val playerId = endpointToPlayerId[endpointId] ?: return
        processMovement(playerId, message.stepDelta, message.significant)
    }

    /**
     * Accumulates movement for [playerId] and applies the [MovementPolicy] verdict: a warning
     * flags the player; a forfeit eliminates them for the round, invalidates their bearings,
     * and (Standard) defers the next round until everyone re-greets.
     */
    private fun processMovement(playerId: Int, stepDelta: Int, significant: Boolean) {
        if (!matchInProgress) return
        val activeEngine = engine ?: return
        val steps = (movementSteps[playerId] ?: 0) + stepDelta.coerceAtLeast(0)
        movementSteps[playerId] = steps
        when (MovementPolicy.evaluate(steps, significant)) {
            MovementVerdict.OK -> Unit
            MovementVerdict.WARN -> activeEngine.setMovementWarning(playerId)
            MovementVerdict.FORFEIT -> {
                activeEngine.forfeitPlayer(playerId)
                invalidateBearings(playerId)
            }
        }
    }

    /** Removes [playerId] from every greeting pair so they must re-greet before the next round. */
    private fun invalidateBearings(playerId: Int) {
        synchronized(lobbyLock) {
            bearingMatrix.remove(playerId)
            bearingMatrix.values.forEach { it.remove(playerId) }
        }
    }

    private fun handleCharacterChosen(endpointId: String, message: NetMessage.CharacterChosen) {
        val playerId = endpointToPlayerId[endpointId] ?: return
        synchronized(lobbyLock) {
            updatePlayerLocked(playerId) { p ->
                p.copy(
                    element = message.element,
                    spriteId = message.spriteId,
                    ready = message.element != null || message.spriteId != null,
                )
            }
        }
        broadcastLobbyToAll(_lobby.value?.mode ?: GameMode.STANDARD)
    }

    private fun handlePlayerInput(endpointId: String, message: NetMessage.PlayerInput) {
        // Reject inputs from endpoints not registered in the lobby.
        val trustedId = endpointToPlayerId[endpointId] ?: return
        // Reject non-finite sensor values before they reach game logic.
        if (!message.aimDegrees.isFinite()) return
        if (!message.pitchDegrees.isFinite()) return
        feedInputToEngine(message, trustedId)
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
            is NetMessage.Regreet -> {
                _snapshot.value = null
                _sessionEvents.tryEmit(SessionEvent.RegreetRequired)
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

        val matrix = synchronized(lobbyLock) { bearingMatrix.mapValues { it.value.toMap() } }
        val roundStart = NetMessage.RoundStart(
            mode = mode,
            roundIndex = roundIndex,
            roundDurationSeconds = rules.roundDurationSeconds,
            players = players,
            bearings = matrix,
        )
        transport.broadcast(roundStart)
        _myBearings.value = matrix[HOST_PLAYER_ID].orEmpty()
        _sessionEvents.tryEmit(SessionEvent.RoundStarted)

        val engineSetup = players.map { p ->
            EnginePlayerSetup(
                id = p.id,
                name = p.name,
                bearings = matrix[p.id].orEmpty(),
                element = p.element,
                spriteId = p.spriteId,
            )
        }

        // Create the engine with a scope that is confined to the single-threaded
        // gameLoopDispatcher so all tick-loop state mutations run on one thread (#61).
        // The scope inherits the SupervisorJob from the application scope so that a tick
        // failure does not cancel unrelated coroutines.
        val engineScope = CoroutineScope(scope.coroutineContext + gameLoopDispatcher)
        val activeEngine = engineFactory.create(rules, clock, engineScope)
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
                        // Clear between-round results on both host and client at the same
                        // lifecycle point (before RoundStart is broadcast) so both roles
                        // display the same "stale roundEnd" window (#68).
                        _roundEnd.value = null
                        startOrDeferNextRound()
                    }
                }
                GameMode.KIDS -> _sessionEvents.tryEmit(SessionEvent.MatchOver)
            }
        }
    }

    /**
     * Starts the next standard round, or — if a forfeited player invalidated the bearing
     * matrix — defers it and returns everyone to the lobby greeting view via
     * [SessionEvent.RegreetRequired].
     */
    private fun startOrDeferNextRound() {
        val currentLobby = _lobby.value ?: return
        val players = greetedPlayersOrNull()
        if (players != null) {
            roundIndex++
            startRoundInternal(currentLobby.mode, players)
        } else {
            awaitingRegreet = true
            transport.broadcast(NetMessage.Regreet)
            broadcastLobbyToAll(currentLobby.mode)
            _sessionEvents.tryEmit(SessionEvent.RegreetRequired)
        }
    }

    /** Resumes a deferred next round once every pair is greeted again; otherwise re-broadcasts. */
    private fun maybeResumeAfterRegreet() {
        val currentLobby = _lobby.value ?: return
        val players = greetedPlayersOrNull()
        if (players != null) {
            awaitingRegreet = false
            roundIndex++
            startRoundInternal(currentLobby.mode, players)
        } else {
            broadcastLobbyToAll(currentLobby.mode)
        }
    }

    /** Returns the current lobby players when every ordered pair is greeted, else null. */
    private fun greetedPlayersOrNull(): List<LobbyPlayer>? = synchronized(lobbyLock) {
        val players = lobbyPlayers.toList()
        if (allPairsGreeted(players)) players else null
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
            PlayerAction.ATTACK -> input.action
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
        val players: List<LobbyPlayer>
        val matrix: Map<Int, Map<Int, Float>>
        synchronized(lobbyLock) {
            matrix = bearingMatrix.mapValues { it.value.toMap() }
            players = lobbyPlayers.map { p ->
                p.copy(
                    outgoingBearings = matrix[p.id].orEmpty(),
                    ready = hasCharacter(p, mode) && hasGreetedAllLocked(p.id),
                )
            }
        }
        _lobby.value = NetMessage.LobbyState(
            mode = mode,
            players = players,
            yourPlayerId = HOST_PLAYER_ID,
            yourBearings = matrix[HOST_PLAYER_ID].orEmpty(),
        )
        endpointToPlayerId.forEach { (endpointId, playerId) ->
            transport.send(
                endpointId,
                NetMessage.LobbyState(
                    mode = mode,
                    players = players,
                    yourPlayerId = playerId,
                    yourBearings = matrix[playerId].orEmpty(),
                ),
            )
        }
    }

    private fun hasCharacter(player: LobbyPlayer, mode: GameMode): Boolean = when (mode) {
        GameMode.STANDARD -> player.element != null
        GameMode.KIDS -> player.spriteId != null
    }

    /** True when [playerId] has greeted every other current lobby player. Hold [lobbyLock]. */
    private fun hasGreetedAllLocked(playerId: Int): Boolean {
        val outgoing = bearingMatrix[playerId] ?: return false
        return lobbyPlayers.filter { it.id != playerId }.all { outgoing.containsKey(it.id) }
    }

    /** True when every ordered pair of [players] has a captured bearing. Hold [lobbyLock]. */
    private fun allPairsGreeted(players: List<LobbyPlayer>): Boolean =
        players.all { hasGreetedAllLocked(it.id) }

    /**
     * Mutates the host player's [LobbyPlayer] entry.
     *
     * Must be called while holding [lobbyLock] when accessed from a coroutine that may
     * race with transport-collector coroutines (#61).
     */
    private fun updateHostPlayer(transform: (LobbyPlayer) -> LobbyPlayer) {
        synchronized(lobbyLock) {
            val idx = lobbyPlayers.indexOfFirst { it.id == HOST_PLAYER_ID }
            if (idx >= 0) lobbyPlayers[idx] = transform(lobbyPlayers[idx])
        }
    }

    /**
     * Mutates a [LobbyPlayer] by [playerId] **without** acquiring [lobbyLock].
     *
     * Callers are responsible for holding [lobbyLock] before calling this function.
     */
    private fun updatePlayerLocked(playerId: Int, transform: (LobbyPlayer) -> LobbyPlayer) {
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
        _myBearings.value = emptyMap()
        synchronized(lobbyLock) {
            lobbyPlayers.clear()
            bearingMatrix.clear()
        }
        movementSteps.clear()
        awaitingRegreet = false
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
        private const val MAX_PLAYER_NAME_LENGTH = 24
        private const val FULL_CIRCLE = 360f

        /** Buffer for session-level navigation events. */
        private const val SESSION_BUFFER = 16

        /** Delay (ms) between the ROUND_OVER snapshot and broadcasting RoundEnd. */
        private const val ROUND_END_DELAY_MILLIS = 200L

        /** Delay (ms) between RoundEnd and auto-starting the next round in standard mode. */
        private const val NEXT_ROUND_DELAY_MILLIS = 3_000L
    }
}
