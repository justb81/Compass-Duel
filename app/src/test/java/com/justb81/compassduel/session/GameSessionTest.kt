package com.justb81.compassduel.session

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.game.engine.ModeRuleSet
import com.justb81.compassduel.game.engine.RoundOutcome
import com.justb81.compassduel.game.engine.StandardRuleSet
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
import com.justb81.compassduel.net.ConnectionEvent
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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

    /** All messages sent via [send] or [broadcast], in order. */
    val sentMessages: MutableList<NetMessage> = mutableListOf()

    /** Messages delivered to specific endpoints via [send], paired with endpointId. */
    val sentToEndpoint: MutableList<Pair<String, NetMessage>> = mutableListOf()

    var advertisingStarted: Boolean = false
    var discoveryStarted: Boolean = false
    var discoveryStopped: Boolean = false
    var allStopped: Boolean = false

    override fun startAdvertising(localName: String) { advertisingStarted = true }
    override fun startDiscovery() { discoveryStarted = true }
    override fun stopDiscovery() { discoveryStopped = true }
    override fun requestConnection(endpointId: String, localName: String) = Unit

    override fun send(endpointId: String, message: NetMessage) {
        sentMessages += message
        sentToEndpoint += endpointId to message
    }

    override fun broadcast(message: NetMessage) {
        sentMessages += message
    }

    override fun stopAll() {
        allStopped = true
    }

    suspend fun emitConnection(event: ConnectionEvent) { _connectionEvents.emit(event) }
    suspend fun emitIncoming(endpointId: String, message: NetMessage) {
        _incomingMessages.emit(endpointId to message)
    }

    companion object {
        private const val BUFFER_CAPACITY = 64
    }
}

/**
 * Minimal [GameEngine] stand-in that never ticks, used to verify session wiring
 * without running real game logic in these unit tests.
 */
private class NoOpEngine(rules: ModeRuleSet, clock: GameClock, scope: CoroutineScope) :
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
            scope = testScope,
        )
    }

    // ---------------------------------------------------------------------------
    // Host lobby tests
    // ---------------------------------------------------------------------------

    @Test
    fun `hostLobby sets role to HOST and starts advertising`() = testScope.runTest {
        val session = buildSession()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        advanceUntilIdle()

        assertEquals(SessionRole.HOST, session.role.value)
        assertTrue(transport.advertisingStarted)
    }

    @Test
    fun `hostLobby creates lobby state with host player`() = testScope.runTest {
        val session = buildSession()

        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        advanceUntilIdle()

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
        advanceUntilIdle()

        session.setMode(GameMode.KIDS)
        advanceUntilIdle()

        assertEquals(GameMode.KIDS, session.lobby.value?.mode)
    }

    // ---------------------------------------------------------------------------
    // Client lobby tests
    // ---------------------------------------------------------------------------

    @Test
    fun `joinLobby sets role to CLIENT and starts discovery`() = testScope.runTest {
        val session = buildSession()

        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()

        assertEquals(SessionRole.CLIENT, session.role.value)
        assertTrue(transport.discoveryStarted)
    }

    @Test
    fun `connectTo sends ClientHello after connected event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()

        session.connectTo(endpointId = "host-ep")
        advanceUntilIdle()

        // Simulate the connected event from transport
        transport.emitConnection(ConnectionEvent.Connected("host-ep", "host-ep"))
        advanceUntilIdle()

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
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        advanceUntilIdle()

        val lobby = session.lobby.value
        assertEquals(2, lobby?.players?.size)
        assertTrue(lobby?.players?.any { it.name == "Bob" } == true)
    }

    @Test
    fun `host receives SeatChosen and updates lobby`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.SeatChosen(cell = 3))
        advanceUntilIdle()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertEquals(3, bob?.seatCell)
    }

    @Test
    fun `host receives CharacterChosen and marks player ready`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.CharacterChosen(element = Element.FIRE))
        advanceUntilIdle()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertTrue(bob?.ready == true)
        assertEquals(Element.FIRE, bob?.element)
    }

    // ---------------------------------------------------------------------------
    // Client incoming message handling
    // ---------------------------------------------------------------------------

    @Test
    fun `client receives LobbyState and updates lobby flow`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()

        val lobbyState = NetMessage.LobbyState(
            mode = GameMode.KIDS,
            players = emptyList(),
            yourPlayerId = 2,
        )
        transport.emitIncoming("host-ep", lobbyState)
        advanceUntilIdle()

        assertEquals(GameMode.KIDS, session.lobby.value?.mode)
    }

    @Test
    fun `client receives RoundStart and emits RoundStarted event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()

        val eventsDeferred = testScope.async { session.sessionEvents.first() }
        advanceUntilIdle()

        transport.emitIncoming(
            "host-ep",
            NetMessage.RoundStart(
                mode = GameMode.STANDARD,
                roundIndex = 0,
                roundDurationSeconds = 90,
                players = emptyList(),
                facingCaptureSeconds = 3,
            ),
        )
        advanceUntilIdle()

        assertEquals(SessionEvent.RoundStarted, eventsDeferred.await())
    }

    @Test
    fun `client receives Rematch and emits RematchRequested event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()

        val eventsDeferred = testScope.async { session.sessionEvents.first() }
        advanceUntilIdle()

        transport.emitIncoming("host-ep", NetMessage.Rematch)
        advanceUntilIdle()

        assertEquals(SessionEvent.RematchRequested, eventsDeferred.await())
    }

    // ---------------------------------------------------------------------------
    // Seat and character selection
    // ---------------------------------------------------------------------------

    @Test
    fun `host chooseSeat updates lobby and does not send transport message`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        val countBefore = transport.sentMessages.size
        advanceUntilIdle()

        session.chooseSeat(cell = 2)
        advanceUntilIdle()

        val alice = session.lobby.value?.players?.find { it.name == "Alice" }
        assertEquals(2, alice?.seatCell)
        // Host does not send SeatChosen over transport — it mutates local state directly
        assertTrue(transport.sentMessages.drop(countBefore).none { it is NetMessage.SeatChosen })
    }

    @Test
    fun `client chooseSeat sends SeatChosen to host endpoint`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()
        session.connectTo("host-ep")
        advanceUntilIdle()

        session.chooseSeat(cell = 5)
        advanceUntilIdle()

        val sent = transport.sentMessages.filterIsInstance<NetMessage.SeatChosen>()
        assertTrue(sent.isNotEmpty()) { "Expected SeatChosen to be sent" }
        assertEquals(5, sent.last().cell)
    }

    // ---------------------------------------------------------------------------
    // Leave and reset
    // ---------------------------------------------------------------------------

    @Test
    fun `leave resets all state and stops transport`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        advanceUntilIdle()

        session.leave()
        advanceUntilIdle()

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
        advanceUntilIdle()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        advanceUntilIdle()

        transport.emitConnection(ConnectionEvent.Disconnected("client-ep"))
        advanceUntilIdle()

        val lobby = session.lobby.value
        assertEquals(1, lobby?.players?.size)
        assertNull(lobby?.players?.find { it.name == "Bob" })
    }

    @Test
    fun `client receives host disconnection emits PeerLost`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        advanceUntilIdle()
        session.connectTo("host-ep")
        advanceUntilIdle()

        val eventsDeferred = testScope.async { session.sessionEvents.first() }
        advanceUntilIdle()

        transport.emitConnection(ConnectionEvent.Disconnected("host-ep"))
        advanceUntilIdle()

        assertEquals(SessionEvent.PeerLost, eventsDeferred.await())
    }

    // ---------------------------------------------------------------------------
    // Kids round-end outcome wiring
    // ---------------------------------------------------------------------------

    @Test
    fun `kids round end carries non-zero bubbleBlocks and sparklesThrown from engine outcome`() = testScope.runTest {
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
        advanceUntilIdle()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        advanceUntilIdle()

        session.chooseSeat(cell = 0)
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
        advanceUntilIdle()

        session.chooseCharacter(spriteId = 0)
        transport.emitIncoming("ep2", NetMessage.CharacterChosen(spriteId = 1))
        advanceUntilIdle()

        session.startMatch()
        advanceUntilIdle()

        // Advance fake clock past countdown (3 s) then past round duration (60 s),
        // and advance the coroutine scheduler by the same amount so the tick loop runs.
        val countdownMs = 3_100L
        val roundMs = 60_100L
        clock.advance(countdownMs)
        advanceTimeBy(countdownMs)
        advanceUntilIdle()

        clock.advance(roundMs)
        advanceTimeBy(roundMs)
        advanceUntilIdle()

        // Allow the ROUND_END_DELAY_MILLIS (200 ms) to elapse
        val roundEndDelayMs = 200L
        clock.advance(roundEndDelayMs)
        advanceTimeBy(roundEndDelayMs)
        advanceUntilIdle()

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
    fun `startMatch throws when fewer than 2 players`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        session.chooseSeat(cell = 0)
        session.chooseCharacter(element = Element.FIRE)
        advanceUntilIdle()

        var threw = false
        try {
            session.startMatch()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw) { "Expected IllegalStateException for single player" }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun assertTrue(condition: Boolean, lazyMessage: () -> String) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, lazyMessage)
    }

    private fun <T> TestScope.async(block: suspend () -> T): kotlinx.coroutines.Deferred<T> =
        this.backgroundScope.async { block() }
}

// Extension to use backgroundScope easily in tests
private fun <T> kotlinx.coroutines.CoroutineScope.async(
    block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
): kotlinx.coroutines.Deferred<T> = kotlinx.coroutines.async { block() }
