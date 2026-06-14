package com.justb81.compassduel.session

import app.cash.turbine.test
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.game.engine.ModeRuleSet
import com.justb81.compassduel.game.engine.RoundOutcome
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
import com.justb81.compassduel.net.AdvertisedLobby
import com.justb81.compassduel.net.ConnectionEvent
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.TransportError
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.net.protocol.PlayerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Controllable [GameClock] for session tests.
 *
 * Note: [FakeClock] is defined in GameEngineTest.kt in a different package so it is
 * not visible here — this local version serves the same purpose.
 */
private class SessionTestClock(private val startMillis: Long = 1_000_000L) : GameClock {
    private var offsetMillis: Long = 0L
    override fun nowMillis(): Long = startMillis + offsetMillis
    fun advance(millis: Long) { offsetMillis += millis }
}

/**
 * Virtual time to advance past the host/client reconnect grace windows
 * (`*_RECONNECT_GRACE_MILLIS` = 8 s in [GameSession]) so a grace timer fires in tests.
 */
private const val RECONNECT_GRACE_BUFFER_MILLIS = 8_500L

/**
 * In-memory [MessageTransport] that records sent messages and lets tests inject events.
 *
 * All sent messages are stored in [sentMessages] for assertion in tests. Connection
 * and incoming-message events can be injected via [emitConnection] and [emitIncoming].
 */
private class FakeTransport : MessageTransport {

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    override val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints

    private val _incomingMessages = MutableSharedFlow<Pair<String, NetMessage>>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingMessages: SharedFlow<Pair<String, NetMessage>> = _incomingMessages

    private val _connectedEndpointIds = MutableStateFlow<Set<String>>(emptySet())
    override val connectedEndpointIds: StateFlow<Set<String>> = _connectedEndpointIds

    private val _transportErrors = MutableSharedFlow<TransportError>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val transportErrors: SharedFlow<TransportError> = _transportErrors

    /** All messages sent via [send] or [broadcast], in order. */
    val sentMessages: MutableList<NetMessage> = mutableListOf()

    /** Messages delivered to specific endpoints via [send], paired with endpointId. */
    val sentToEndpoint: MutableList<Pair<String, NetMessage>> = mutableListOf()

    var advertisingStarted: Boolean = false
    var discoveryStarted: Boolean = false
    var discoveryStopped: Boolean = false
    var allStopped: Boolean = false
    override var acceptNewConnections: Boolean = true

    /** The most recent endpoint name passed to [startAdvertising] (encodes mode + player count, #98). */
    var lastAdvertisedName: String? = null

    /** Endpoints passed to [requestConnection], in order, for reconnect assertions. */
    val connectionRequests: MutableList<String> = mutableListOf()

    override fun startAdvertising(localName: String) {
        advertisingStarted = true
        lastAdvertisedName = localName
    }
    override fun startDiscovery() { discoveryStarted = true }
    override fun stopDiscovery() { discoveryStopped = true }
    override fun requestConnection(endpointId: String, localName: String) { connectionRequests += endpointId }

    override fun send(endpointId: String, message: NetMessage) {
        sentMessages += message
        sentToEndpoint += endpointId to message
    }

    override fun broadcast(message: NetMessage) {
        sentMessages += message
    }

    // The reliable variants record alongside the lossy ones so existing message assertions
    // hold; reliability itself is covered by ReliableMessageTransportTest.
    override fun sendReliable(endpointId: String, message: NetMessage) = send(endpointId, message)

    override fun broadcastReliable(message: NetMessage) = broadcast(message)

    override fun stopAll() {
        allStopped = true
    }

    suspend fun emitConnection(event: ConnectionEvent) { _connectionEvents.emit(event) }
    suspend fun emitIncoming(endpointId: String, message: NetMessage) {
        _incomingMessages.emit(endpointId to message)
    }
    suspend fun emitTransportError(error: TransportError) { _transportErrors.emit(error) }

    /** Drives [discoveredEndpoints] so client reconnect/rediscovery logic can be exercised. */
    fun setDiscovered(endpoints: List<DiscoveredEndpoint>) { _discoveredEndpoints.value = endpoints }

    companion object {
        private const val BUFFER_CAPACITY = 64
    }
}

/**
 * Minimal [GameEngine] stand-in that never ticks, used to verify session wiring
 * without running real game logic in these unit tests.
 */
private open class NoOpEngine(rules: ModeRuleSet, clock: GameClock, scope: CoroutineScope) :
    GameEngine(rules, clock, scope)

/**
 * [GameEngine] stand-in that returns a pre-configured [RoundOutcome] from [roundOutcome],
 * allowing tests to verify that [GameSession] uses the engine outcome rather than
 * deriving stats from the snapshot.
 */
private class StubEngine(
    rules: ModeRuleSet,
    clock: GameClock,
    scope: CoroutineScope,
    private val stubbedOutcome: RoundOutcome,
) : GameEngine(rules, clock, scope) {
    override fun roundOutcome(): RoundOutcome = stubbedOutcome
}

/**
 * [GameEngine] stand-in that exposes [triggerRoundOver] so tests can fire the
 * [roundOverSignal] on demand, exercising the round-lifecycle wiring in [GameSession]
 * without running a real tick loop.
 *
 * [roundOutcome] always returns [stubbedOutcome] regardless of internal phase state.
 */
private class ControllableEngine(
    rules: ModeRuleSet,
    clock: GameClock,
    scope: CoroutineScope,
    private val stubbedOutcome: RoundOutcome,
) : GameEngine(rules, clock, scope) {

    /** Completes the [roundOverSignal] deferred, simulating a legitimate ROUND_OVER. */
    fun triggerRoundOver() {
        roundOverSignal.complete(Unit)
    }

    override fun roundOutcome(): RoundOutcome = stubbedOutcome
}

// Aggregates host/client lobby, security trust-boundary and round-lifecycle scenarios for
// GameSession; the shared FakeTransport/clock/engine harness keeps these in one suite.
@Suppress("LargeClass")
class GameSessionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val clock = SessionTestClock()
    private val transport = FakeTransport()

    private fun buildSession(engineFactory: GameEngineFactory? = null): GameSession {
        val factory = engineFactory ?: GameEngineFactory { rules, clk, scp -> NoOpEngine(rules, clk, scp) }
        return GameSession(
            transport = transport,
            clock = clock,
            engineFactory = factory,
            // backgroundScope: the session's long-lived collectors are cancelled with the
            // test instead of tripping runTest's UncompletedCoroutinesError leak check.
            scope = testScope.backgroundScope,
            // Use the test dispatcher as the game-loop dispatcher so engine ticks and
            // session-state mutations are controlled by the test scheduler (#61).
            gameLoopDispatcher = testDispatcher,
        )
    }

    /** Hosts a KIDS match with the host + one client (ep2), all greeted and ready. */
    private suspend fun startTwoPlayerKidsMatch(session: GameSession) {
        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()
        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        yield()
        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()
        session.startMatch()
        yield()
    }

    /** Hosts a KIDS match with the host + two clients (ep2, ep3), every pair greeted and ready. */
    private suspend fun startThreePlayerKidsMatch(session: GameSession) {
        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        transport.emitIncoming("ep3", NetMessage.ClientHello("Carol"))
        yield()
        // Every ordered pair greets: host→{2,3}, 2→{1,3}, 3→{1,2}.
        session.submitBow(toPlayerId = 2, bearingDegrees = 120f)
        session.submitBow(toPlayerId = 3, bearingDegrees = 240f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 3, bearingDegrees = 200f))
        transport.emitIncoming("ep3", NetMessage.Greeting(fromPlayerId = 3, toPlayerId = 1, bearingDegrees = 60f))
        transport.emitIncoming("ep3", NetMessage.Greeting(fromPlayerId = 3, toPlayerId = 2, bearingDegrees = 20f))
        yield()
        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        transport.emitIncoming("ep3", NetMessage.CharacterChosen(spriteId = 2))
        yield()
        session.startMatch()
        yield()
    }

    // ---------------------------------------------------------------------------
    // Host lobby tests
    // ---------------------------------------------------------------------------

    @Test
    fun `hostLobby sets role to HOST and starts advertising`() = testScope.runTest {
        val session = buildSession()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        assertEquals(SessionRole.HOST, session.role.value)
        assertTrue(transport.advertisingStarted)
    }

    @Test
    fun `hostLobby creates lobby state with host player`() = testScope.runTest {
        val session = buildSession()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        val lobby = session.lobby.value
        assertNotNull(lobby)
        assertEquals(GameMode.STANDARD, lobby?.mode)
        assertEquals(1, lobby?.players?.size)
        assertEquals("Alice", lobby?.players?.first()?.name)
    }

    @Test
    fun `setMode rebroadcasts lobby with new mode`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        session.setMode(GameMode.KIDS)
        yield()

        assertEquals(GameMode.KIDS, session.lobby.value?.mode)
    }

    // ---------------------------------------------------------------------------
    // Session configuration & discovery advertisement (#98, #101)
    // ---------------------------------------------------------------------------

    @Test
    fun `hostLobby advertises an endpoint name encoding mode and player count`() = testScope.runTest {
        val session = buildSession()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        val decoded = AdvertisedLobby.decode(transport.lastAdvertisedName!!)
        assertEquals("Alice", decoded.name)
        assertEquals(GameMode.STANDARD, decoded.mode)
        assertEquals(1, decoded.playerCount)
    }

    @Test
    fun `advertised player count updates when a client joins`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        assertEquals(2, AdvertisedLobby.decode(transport.lastAdvertisedName!!).playerCount)
    }

    @Test
    fun `selected round length and best-of are broadcast in lobby state`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        session.setRoundDuration(30)
        session.setBestOf(5)
        yield()

        assertEquals(30, session.lobby.value?.roundDurationSeconds)
        assertEquals(5, session.lobby.value?.bestOf)
    }

    @Test
    fun `kids mode forces best-of to one regardless of selection`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()
        session.setBestOf(5)
        yield()

        session.setMode(GameMode.KIDS)
        yield()

        assertEquals(1, session.lobby.value?.bestOf)
    }

    // ---------------------------------------------------------------------------
    // Client lobby tests
    // ---------------------------------------------------------------------------

    @Test
    fun `startBrowsing starts discovery and leaves role null`() = testScope.runTest {
        val session = buildSession()

        session.startBrowsing()
        yield()

        assertNull(session.role.value)
        assertTrue(transport.discoveryStarted)
    }

    @Test
    fun `stopBrowsing stops discovery`() = testScope.runTest {
        val session = buildSession()
        session.startBrowsing()

        session.stopBrowsing()
        yield()

        assertTrue(transport.discoveryStopped)
    }

    @Test
    fun `joinLobby commits to endpoint, stops discovery and sets CLIENT`() = testScope.runTest {
        val session = buildSession()
        session.startBrowsing()
        yield()

        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        assertEquals(SessionRole.CLIENT, session.role.value)
        assertTrue(transport.discoveryStopped)
    }

    @Test
    fun `hostLobby after browsing stops discovery before advertising`() = testScope.runTest {
        val session = buildSession()
        session.startBrowsing()
        yield()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        assertEquals(SessionRole.HOST, session.role.value)
        assertTrue(transport.discoveryStopped)
        assertTrue(transport.advertisingStarted)
    }

    @Test
    fun `joinLobby sends ClientHello after connected event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        // Simulate the connected event from transport.
        transport.emitConnection(ConnectionEvent.Connected("host-ep", "host-ep"))
        yield()

        val helloMessages = transport.sentMessages.filterIsInstance<NetMessage.ClientHello>()
        assertEquals(1, helloMessages.size)
        assertEquals("Bob", helloMessages.first().playerName)
    }

    // ---------------------------------------------------------------------------
    // Host incoming message handling
    // ---------------------------------------------------------------------------

    @Test
    fun `host receives ClientHello and adds player to lobby`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        val lobby = session.lobby.value
        assertEquals(2, lobby?.players?.size)
        assertTrue(lobby?.players?.any { it.name == "Bob" } == true)
    }

    @Test
    fun `host receives Greeting and records the bearing on the lobby`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitIncoming(
            "client-ep",
            NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 137.5f),
        )
        yield()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertEquals(137.5f, bob?.outgoingBearings?.get(1))
    }

    @Test
    fun `host receives CharacterChosen and marks player ready once greeted`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitIncoming("client-ep", NetMessage.CharacterChosen(element = Element.FIRE))
        yield()

        // Character chosen but Bob has not greeted Alice yet — not ready.
        val bobBeforeGreeting = session.lobby.value?.players?.find { it.name == "Bob" }
        assertEquals(Element.FIRE, bobBeforeGreeting?.element)
        assertFalse(bobBeforeGreeting?.ready == true)

        // Once Bob greets the only other player he is ready.
        transport.emitIncoming(
            "client-ep",
            NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f),
        )
        yield()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertTrue(bob?.ready == true)
    }

    // ---------------------------------------------------------------------------
    // Client incoming message handling
    // ---------------------------------------------------------------------------

    @Test
    fun `client receives LobbyState and updates lobby flow`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        val lobbyState = NetMessage.LobbyState(
            mode = GameMode.KIDS,
            players = emptyList(),
            yourPlayerId = 2,
        )
        transport.emitIncoming("host-ep", lobbyState)
        yield()

        assertEquals(GameMode.KIDS, session.lobby.value?.mode)
    }

    @Test
    fun `client receives RoundStart and emits RoundStarted event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        session.sessionEvents.test {
            transport.emitIncoming(
                "host-ep",
                NetMessage.RoundStart(
                    mode = GameMode.STANDARD,
                    roundIndex = 0,
                    roundDurationSeconds = 90,
                    players = emptyList(),
                ),
            )
            yield()
            assertEquals(SessionEvent.RoundStarted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `client receives Rematch and emits RematchRequested event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        session.sessionEvents.test {
            transport.emitIncoming("host-ep", NetMessage.Rematch)
            yield()
            assertEquals(SessionEvent.RematchRequested, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `transport errors are re-exposed on the session`() = testScope.runTest {
        val session = buildSession()

        session.transportErrors.test {
            transport.emitTransportError(TransportError.ADVERTISE)
            assertEquals(TransportError.ADVERTISE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------------------
    // Seat and character selection
    // ---------------------------------------------------------------------------

    @Test
    fun `host submitBow records the bearing locally without a transport message`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()
        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()
        val countBefore = transport.sentMessages.size

        session.submitBow(toPlayerId = 2, bearingDegrees = 90f)
        yield()

        val alice = session.lobby.value?.players?.find { it.name == "Alice" }
        assertEquals(90f, alice?.outgoingBearings?.get(2))
        // Host does not send a Greeting over transport — it records local state directly.
        assertTrue(transport.sentMessages.drop(countBefore).none { it is NetMessage.Greeting })
    }

    @Test
    fun `client submitBow sends Greeting to host endpoint`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()
        // The client must know its own id before it can send a Greeting.
        transport.emitIncoming(
            "host-ep",
            NetMessage.LobbyState(mode = GameMode.STANDARD, players = emptyList(), yourPlayerId = 2),
        )
        yield()

        session.submitBow(toPlayerId = 1, bearingDegrees = 270f)
        yield()

        val sent = transport.sentMessages.filterIsInstance<NetMessage.Greeting>()
        assertTrue(sent.isNotEmpty()) { "Expected Greeting to be sent" }
        assertEquals(1, sent.last().toPlayerId)
        assertEquals(270f, sent.last().bearingDegrees)
    }

    // ---------------------------------------------------------------------------
    // Leave and reset
    // ---------------------------------------------------------------------------

    @Test
    fun `leave resets all state and stops transport`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        session.leave()
        yield()

        assertNull(session.role.value)
        assertNull(session.lobby.value)
        assertTrue(transport.allStopped)
    }

    // ---------------------------------------------------------------------------
    // Disconnection handling
    // ---------------------------------------------------------------------------

    @Test
    fun `host receives peer disconnection in lobby removes player`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitConnection(ConnectionEvent.Disconnected("client-ep"))
        yield()

        val lobby = session.lobby.value
        assertEquals(1, lobby?.players?.size)
        assertNull(lobby?.players?.find { it.name == "Bob" })
    }

    @Test
    fun `client host loss enters reconnect grace and re-requests the rediscovered host`() =
        testScope.runTest {
            val session = buildSession()
            session.joinLobby(playerName = "Bob", endpointId = "host-ep")
            yield()
            // A prior Connected captures the host name so rediscovery can re-match it.
            transport.emitConnection(ConnectionEvent.Connected("host-ep", "Alice"))
            yield()

            session.sessionEvents.test {
                transport.emitConnection(ConnectionEvent.Disconnected("host-ep"))
                yield()
                assertEquals(SessionEvent.PeerReconnecting, awaitItem())
                assertTrue(transport.discoveryStarted)

                // The host reappears under a new endpoint id; the client re-requests it.
                transport.setDiscovered(listOf(DiscoveredEndpoint("host-ep2", "Alice")))
                yield()
                assertTrue(transport.connectionRequests.contains("host-ep2"))

                // Re-establishing the link within the grace window dismisses the overlay.
                transport.emitConnection(ConnectionEvent.Connected("host-ep2", "Alice"))
                yield()
                assertEquals(SessionEvent.PeerReconnected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `client host loss falls back to PeerLost after the reconnect grace window`() =
        testScope.runTest {
            val session = buildSession()
            session.joinLobby(playerName = "Bob", endpointId = "host-ep")
            yield()

            session.sessionEvents.test {
                transport.emitConnection(ConnectionEvent.Disconnected("host-ep"))
                yield()
                assertEquals(SessionEvent.PeerReconnecting, awaitItem())

                // No reconnection arrives; the grace window (8 s) elapses → terminal PeerLost.
                delay(RECONNECT_GRACE_BUFFER_MILLIS)
                assertEquals(SessionEvent.PeerLost, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `host holds dropped player slot during grace and resumes them on reconnect`() =
        testScope.runTest {
            var forfeitedId: Int? = null
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    object : NoOpEngine(rules, clk, scp) {
                        override fun forfeitPlayer(playerId: Int) { forfeitedId = playerId }
                    }
                },
            )
            startThreePlayerKidsMatch(session)

            // Bob (ep2) drops mid-match, then reconnects under a new endpoint before the window ends.
            transport.emitConnection(ConnectionEvent.Disconnected("ep2"))
            yield()
            transport.emitIncoming("ep2b", NetMessage.ClientHello("Bob"))
            yield()

            // Reconnected: a fresh RoundStart is replayed to the returning endpoint.
            assertTrue(transport.sentToEndpoint.any { it.first == "ep2b" && it.second is NetMessage.RoundStart })

            // Even after the original grace window would have elapsed, Bob is not forfeited.
            delay(RECONNECT_GRACE_BUFFER_MILLIS)
            assertNull(forfeitedId)
        }

    @Test
    fun `host forfeits dropped player and continues when enough players remain`() =
        testScope.runTest {
            var forfeitedId: Int? = null
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    object : NoOpEngine(rules, clk, scp) {
                        override fun forfeitPlayer(playerId: Int) { forfeitedId = playerId }
                    }
                },
            )
            startThreePlayerKidsMatch(session)

            session.sessionEvents.test {
                assertEquals(SessionEvent.RoundStarted, awaitItem()) // replayed match-start event
                transport.emitConnection(ConnectionEvent.Disconnected("ep2"))
                yield()
                // Grace elapses with 2 players (host + Carol) still present → forfeit, no abort.
                delay(RECONNECT_GRACE_BUFFER_MILLIS)
                assertEquals(2, forfeitedId)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, session.lobby.value?.players?.size)
        }

    @Test
    fun `host aborts match with PeerLost when a dropout leaves too few players`() =
        testScope.runTest {
            val session = buildSession()
            startTwoPlayerKidsMatch(session)

            session.sessionEvents.test {
                assertEquals(SessionEvent.RoundStarted, awaitItem()) // replayed match-start event
                transport.emitConnection(ConnectionEvent.Disconnected("ep2"))
                yield()
                // Only the host would remain (< MIN_PLAYERS) → the match aborts.
                delay(RECONNECT_GRACE_BUFFER_MILLIS)
                assertEquals(SessionEvent.PeerLost, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---------------------------------------------------------------------------
    // Kids round-end outcome wiring
    // ---------------------------------------------------------------------------

    @Test
    fun `kids round end carries non-zero bubbleBlocks and sparklesThrown from engine outcome`() =
        testScope.runTest {
            val aliceId = 1
            val bobId = 2
            val kidsStats = mapOf(
                aliceId to KidsRoundStats(playerId = aliceId, stars = 3, bubbleBlocks = 2, sparklesThrown = 5),
                bobId to KidsRoundStats(playerId = bobId, stars = 1, bubbleBlocks = 0, sparklesThrown = 3),
            )
            val kidsAwards = mapOf(aliceId to KidsAward.STAR_CHAMPION, bobId to KidsAward.SUPER_SPARKLER)
            val stubbedOutcome = RoundOutcome.KidsOutcome(stats = kidsStats, awards = kidsAwards)

            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    StubEngine(rules, clk, scp, stubbedOutcome)
                },
            )

            session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
            yield()

            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()

            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()

            session.chooseCharacter(spriteId = 0)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
            yield()

            session.startMatch()
            yield()

            // Advance the fake clock and virtual time in lockstep. Suspending the test
            // body via delay() is what lets backgroundScope work (collectors, the
            // engine tick loop) run: the test scheduler only interleaves background
            // coroutines while the foreground test body is suspended.
            val countdownMs = 3_100L
            val roundMs = 60_100L
            clock.advance(countdownMs)
            delay(countdownMs)

            clock.advance(roundMs)
            delay(roundMs)

            // Allow the ROUND_END_DELAY_MILLIS (200 ms) delay inside
            // computeAndBroadcastRoundEnd to elapse.
            val roundEndDelayMs = 200L
            clock.advance(roundEndDelayMs)
            delay(roundEndDelayMs)

            val roundEnd = session.roundEnd.value
            assertNotNull(roundEnd)
            assertNotNull(roundEnd?.kidsStats)

            val aliceStats = roundEnd!!.kidsStats!!.find { it.playerId == aliceId }
            assertNotNull(aliceStats)
            assertEquals(2, aliceStats!!.bubbleBlocks)
            assertEquals(5, aliceStats.sparklesThrown)

            val bobStats = roundEnd.kidsStats!!.find { it.playerId == bobId }
            assertNotNull(bobStats)
            assertEquals(3, bobStats!!.sparklesThrown)
        }

    // ---------------------------------------------------------------------------
    // startMatch validation
    // ---------------------------------------------------------------------------

    @Test
    fun `startMatch returns NoLobby when no lobby exists`() = testScope.runTest {
        val session = buildSession()

        assertEquals(StartResult.NoLobby, session.startMatch())
    }

    @Test
    fun `startMatch returns TooFewPlayers when only the host is present`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        session.chooseCharacter(element = Element.FIRE)
        yield()

        assertEquals(StartResult.TooFewPlayers, session.startMatch())
    }

    @Test
    fun `startMatch returns MissingElements when a standard player has no element`() =
        testScope.runTest {
            val session = buildSession()
            session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
            yield()
            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()
            // Both pairs greet, but neither player picks an element.
            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()

            assertEquals(StartResult.MissingElements, session.startMatch())
        }

    @Test
    fun `startMatch returns DuplicateElements when two players pick the same element`() =
        testScope.runTest {
            val session = buildSession()
            session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
            yield()
            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()
            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()
            session.chooseCharacter(element = Element.FIRE)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(element = Element.FIRE))
            yield()

            assertEquals(StartResult.DuplicateElements, session.startMatch())
        }

    // ---------------------------------------------------------------------------
    // Security: host trust boundary (#39 - #43)
    // ---------------------------------------------------------------------------

    @Test
    fun `host ignores PlayerInput from unmapped endpoint`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        // "unknown-ep" has never sent ClientHello, so it is not in endpointToPlayerId.
        // The input should be silently dropped — no exception thrown.
        transport.emitIncoming(
            "unknown-ep",
            NetMessage.PlayerInput(
                playerId = 99,
                aimDegrees = 0f,
                pitchDegrees = 0f,
                action = PlayerAction.IDLE,
            ),
        )
        yield()

        // Session remains in the initial lobby state — no crash, no state corruption.
        assertEquals(1, session.lobby.value?.players?.size)
    }

    @Test
    fun `host ignores PlayerInput with mismatched playerId — uses trusted mapped id`() =
        testScope.runTest {
            var capturedPlayerId: Int? = null
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    object : NoOpEngine(rules, clk, scp) {
                        override fun submitInput(
                            playerId: Int,
                            aimDegrees: Float,
                            isShielding: Boolean,
                            action: PlayerAction?,
                        ) {
                            capturedPlayerId = playerId
                        }
                    }
                },
            )

            session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
            yield()

            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()

            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()

            session.chooseCharacter(spriteId = 0)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
            yield()

            session.startMatch()
            yield()

            // Bob is mapped to id 2. Send a PlayerInput claiming id 99 — host must ignore the
            // client-supplied id and use the trusted mapped id (2) instead.
            transport.emitIncoming(
                "ep2",
                NetMessage.PlayerInput(
                    playerId = 99,
                    aimDegrees = 45f,
                    pitchDegrees = 0f,
                    action = PlayerAction.IDLE,
                ),
            )
            yield()

            assertEquals(2, capturedPlayerId)
        }

    @Test
    fun `client ignores message not from host endpoint`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob", endpointId = "host-ep")
        yield()

        // Inject a LobbyState from a different (untrusted) endpoint.
        transport.emitIncoming(
            "rogue-ep",
            NetMessage.LobbyState(
                mode = GameMode.KIDS,
                players = emptyList(),
                yourPlayerId = 2,
            ),
        )
        yield()

        // Client should have ignored the rogue message — lobby must remain null.
        assertNull(session.lobby.value)
    }

    @Test
    fun `host rejects Greeting with out-of-range bearing`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        // 400° is outside [0, 360) — should be silently rejected.
        transport.emitIncoming(
            "ep2",
            NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 400f),
        )
        yield()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertTrue(bob?.outgoingBearings?.isEmpty() == true)
    }

    @Test
    fun `host rejects PlayerInput with non-finite aimDegrees`() = testScope.runTest {
        var inputReached = false
        val session = buildSession(
            engineFactory = GameEngineFactory { rules, clk, scp ->
                object : NoOpEngine(rules, clk, scp) {
                    override fun submitInput(
                        playerId: Int,
                        aimDegrees: Float,
                        isShielding: Boolean,
                        action: PlayerAction?,
                    ) {
                        inputReached = true
                    }
                }
            },
        )

        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        yield()

        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()

        session.startMatch()
        yield()

        transport.emitIncoming(
            "ep2",
            NetMessage.PlayerInput(
                playerId = 2,
                aimDegrees = Float.POSITIVE_INFINITY,
                pitchDegrees = 0f,
                action = PlayerAction.IDLE,
            ),
        )
        yield()

        assertFalse(inputReached, "Engine must not receive input with non-finite aimDegrees")
    }

    @Test
    fun `host rejects PlayerInput with NaN pitchDegrees`() = testScope.runTest {
        var inputReached = false
        val session = buildSession(
            engineFactory = GameEngineFactory { rules, clk, scp ->
                object : NoOpEngine(rules, clk, scp) {
                    override fun submitInput(
                        playerId: Int,
                        aimDegrees: Float,
                        isShielding: Boolean,
                        action: PlayerAction?,
                    ) {
                        inputReached = true
                    }
                }
            },
        )

        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        yield()

        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()

        session.startMatch()
        yield()

        transport.emitIncoming(
            "ep2",
            NetMessage.PlayerInput(
                playerId = 2,
                aimDegrees = 45f,
                pitchDegrees = Float.NaN,
                action = PlayerAction.IDLE,
            ),
        )
        yield()

        assertFalse(inputReached, "Engine must not receive input with NaN pitchDegrees")
    }

    @Test
    fun `id allocation does not collide after a disconnect`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        // Two clients connect.
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()
        transport.emitIncoming("ep3", NetMessage.ClientHello("Carol"))
        yield()

        val idsBefore = session.lobby.value?.players?.map { it.id }?.toSet()
        assertEquals(3, idsBefore?.size, "Expected 3 distinct ids before disconnect")

        // Bob (ep2) disconnects.
        transport.emitConnection(ConnectionEvent.Disconnected("ep2"))
        yield()

        // A new client connects — must receive the lowest unused id (2), not a duplicate.
        transport.emitIncoming("ep4", NetMessage.ClientHello("Dave"))
        yield()

        val idsAfter = session.lobby.value?.players?.map { it.id }
        assertEquals(idsAfter?.size, idsAfter?.toSet()?.size, "Player ids must be unique after rejoin")

        // Dave should receive id 2 (lowest unused after Bob left).
        val daveId = session.lobby.value?.players?.find { it.name == "Dave" }?.id
        assertEquals(2, daveId)
    }

    @Test
    fun `transport acceptNewConnections is false after startMatch`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        yield()

        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()

        assertTrue(transport.acceptNewConnections)

        session.startMatch()
        yield()

        assertFalse(transport.acceptNewConnections)
    }

    @Test
    fun `host rejects ClientHello when lobby is full`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        // Fill the lobby to MAX_PLAYERS (4 total: 1 host + 3 clients).
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()
        transport.emitIncoming("ep3", NetMessage.ClientHello("Carol"))
        yield()
        transport.emitIncoming("ep4", NetMessage.ClientHello("Dave"))
        yield()

        assertEquals(4, session.lobby.value?.players?.size)

        // A fifth player tries to join — must be rejected.
        transport.emitIncoming("ep5", NetMessage.ClientHello("Eve"))
        yield()

        assertEquals(4, session.lobby.value?.players?.size)
        assertNull(session.lobby.value?.players?.find { it.name == "Eve" })
    }

    @Test
    fun `startMatch requires every pair to greet, then succeeds`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()
        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()

        // Only the client has greeted the host so far — the matrix is incomplete.
        transport.emitIncoming(
            "ep2",
            NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f),
        )
        yield()
        assertEquals(StartResult.NotAllGreeted, session.startMatch())

        // Host bows back — now every ordered pair is greeted and the match can start.
        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        yield()
        assertEquals(StartResult.Success, session.startMatch())
        yield()

        val roundStart = transport.sentMessages.filterIsInstance<NetMessage.RoundStart>().lastOrNull()
        assertNotNull(roundStart)
        assertEquals(180f, roundStart?.bearings?.get(1)?.get(2))
        assertEquals(0f, roundStart?.bearings?.get(2)?.get(1))
    }

    @Test
    fun `PlayerMoved past forfeit threshold forfeits the player and invalidates bearings`() =
        testScope.runTest {
            var forfeitedId: Int? = null
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    object : NoOpEngine(rules, clk, scp) {
                        override fun forfeitPlayer(playerId: Int) { forfeitedId = playerId }
                    }
                },
            )
            session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
            yield()
            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()
            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()
            session.chooseCharacter(spriteId = 0)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
            yield()
            session.startMatch()
            yield()

            // A significant-motion report immediately forfeits the client.
            transport.emitIncoming("ep2", NetMessage.PlayerMoved(playerId = 2, significant = true))
            yield()

            assertEquals(2, forfeitedId)
        }

    @Test
    fun `host truncates ClientHello name to 24 characters`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        val longName = "A".repeat(50)
        transport.emitIncoming("ep2", NetMessage.ClientHello(longName))
        yield()

        val newPlayer = session.lobby.value?.players?.find { it.name.length <= 24 && it.name != "Alice" }
        assertNotNull(newPlayer)
        assertEquals(24, newPlayer!!.name.length)
        assertEquals("A".repeat(24), newPlayer.name)
    }

    @Test
    fun `host rejects host-direction messages sent by clients`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        // These message types are only valid host→client; a client sending them must be ignored.
        val lobbySize = session.lobby.value?.players?.size

        transport.emitIncoming(
            "ep2",
            NetMessage.LobbyState(mode = GameMode.KIDS, players = emptyList(), yourPlayerId = 99),
        )
        yield()

        // Lobby must be unaffected — the host's own LobbyState should remain.
        assertEquals(lobbySize, session.lobby.value?.players?.size)
        assertEquals(GameMode.STANDARD, session.lobby.value?.mode)
    }

    @Test
    fun `transport acceptNewConnections is true after requestRematch`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
        transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
        yield()

        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        yield()

        session.startMatch()
        yield()
        assertFalse(transport.acceptNewConnections)

        session.requestRematch()
        yield()

        assertTrue(transport.acceptNewConnections)
    }

    // ---------------------------------------------------------------------------
    // Round lifecycle — #62 roundAdvanceJob cancellation
    // ---------------------------------------------------------------------------

    @Test
    fun `reset cancels roundAdvanceJob so no spurious RoundStart is emitted after leave`() =
        testScope.runTest {
            var engineRef: ControllableEngine? = null
            val stubbedOutcome = RoundOutcome.KidsOutcome(
                stats = mapOf(1 to KidsRoundStats(playerId = 1, stars = 1, bubbleBlocks = 0, sparklesThrown = 0)),
                awards = mapOf(1 to KidsAward.STAR_CHAMPION),
            )
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    ControllableEngine(rules, clk, scp, stubbedOutcome).also { engineRef = it }
                },
            )

            session.hostLobby(playerName = "Alice", mode = GameMode.KIDS)
            yield()
            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()
            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()
            session.chooseCharacter(spriteId = 0)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
            yield()

            session.startMatch()
            yield()

            // Trigger ROUND_OVER to launch roundAdvanceJob (which waits ROUND_END_DELAY then
            // tries to broadcast RoundEnd and emit MatchOver).
            engineRef!!.triggerRoundOver()
            yield()

            // Record sent-messages count before leave.
            val sentBefore = transport.sentMessages.size

            // Leave resets the session — this must cancel roundAdvanceJob so it cannot
            // complete and fire a spurious RoundEnd/MatchOver event (#62).
            session.leave()
            yield()

            // Advance virtual time well past both delay thresholds so roundAdvanceJob
            // would have fired if not cancelled.
            delay(5_000L)

            // No RoundEnd message should have been broadcast after leave().
            val sentAfter = transport.sentMessages.drop(sentBefore)
            assertTrue(sentAfter.none { it is NetMessage.RoundEnd }) {
                "roundAdvanceJob must be cancelled by reset() — spurious RoundEnd found"
            }
        }

    @Test
    fun `requestRematch cancels roundAdvanceJob so no spurious RoundStart is emitted`() =
        testScope.runTest {
            var engineRef: ControllableEngine? = null
            val stubbedOutcome = RoundOutcome.StandardWinner(winnerId = null) // draw, non-final
            val session = buildSession(
                engineFactory = GameEngineFactory { rules, clk, scp ->
                    ControllableEngine(rules, clk, scp, stubbedOutcome).also { engineRef = it }
                },
            )

            session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
            yield()
            transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
            yield()
            session.submitBow(toPlayerId = 2, bearingDegrees = 180f)
            transport.emitIncoming("ep2", NetMessage.Greeting(fromPlayerId = 2, toPlayerId = 1, bearingDegrees = 0f))
            yield()
            session.chooseCharacter(element = Element.FIRE)
            transport.emitIncoming("ep2", NetMessage.CharacterChosen(element = Element.WATER))
            yield()

            session.startMatch()
            yield()

            // Trigger ROUND_OVER — roundAdvanceJob will launch and wait for delays.
            engineRef!!.triggerRoundOver()
            yield()

            // Capture RoundStart count before rematch (there is one from startMatch).
            val roundStartsBefore = transport.sentMessages.count { it is NetMessage.RoundStart }

            // Call requestRematch — this must cancel the in-flight roundAdvanceJob.
            session.requestRematch()
            yield()

            // Advance past all delays — the cancelled roundAdvanceJob must NOT start a
            // new round that would emit another RoundStart (#62).
            delay(5_000L)

            val roundStartsAfter = transport.sentMessages.count { it is NetMessage.RoundStart }
            assertEquals(roundStartsBefore, roundStartsAfter) {
                "requestRematch must cancel roundAdvanceJob — spurious RoundStart found"
            }
        }

    // ---------------------------------------------------------------------------
    // Round lifecycle — #68 client roundEnd cleared between rounds
    // ---------------------------------------------------------------------------

    @Test
    fun `client roundEnd is cleared on next RoundStart for non-final rounds`() =
        testScope.runTest {
            val session = buildSession()
            session.joinLobby(playerName = "Bob", endpointId = "host-ep")
            yield()
            transport.emitConnection(ConnectionEvent.Connected("host-ep", "host-ep"))
            yield()

            // Simulate the host broadcasting a non-final RoundEnd (no matchWinnerId).
            transport.emitIncoming(
                "host-ep",
                NetMessage.RoundEnd(
                    roundWinnerId = 1,
                    matchScore = mapOf(1 to 1, 2 to 0),
                    matchWinnerId = null,
                ),
            )
            yield()

            // Client should have the non-final roundEnd set.
            assertNotNull(session.roundEnd.value) {
                "roundEnd should be set after receiving RoundEnd message"
            }

            // Now the host starts the next round.
            transport.emitIncoming(
                "host-ep",
                NetMessage.RoundStart(
                    mode = GameMode.STANDARD,
                    roundIndex = 1,
                    roundDurationSeconds = 90,
                    players = emptyList(),
                ),
            )
            yield()

            // Client must clear roundEnd on RoundStart — symmetric with host (#68).
            assertNull(session.roundEnd.value) {
                "roundEnd must be null after RoundStart — stale between-round results (#68)"
            }
        }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun assertTrue(condition: Boolean, lazyMessage: () -> String) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, lazyMessage)
    }

    private fun assertNotNull(value: Any?, lazyMessage: () -> String) {
        org.junit.jupiter.api.Assertions.assertNotNull(value, lazyMessage)
    }

    private fun assertNull(value: Any?, lazyMessage: () -> String) {
        org.junit.jupiter.api.Assertions.assertNull(value, lazyMessage)
    }

    private fun assertEquals(expected: Any?, actual: Any?, lazyMessage: () -> String) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, lazyMessage)
    }
}
