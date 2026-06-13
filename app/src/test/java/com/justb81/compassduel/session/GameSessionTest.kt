package com.justb81.compassduel.session

import app.cash.turbine.test
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.game.engine.ModeRuleSet
import com.justb81.compassduel.game.engine.RoundOutcome
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
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
    suspend fun emitTransportError(error: TransportError) { _transportErrors.emit(error) }

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
    // Client lobby tests
    // ---------------------------------------------------------------------------

    @Test
    fun `joinLobby sets role to CLIENT and starts discovery`() = testScope.runTest {
        val session = buildSession()

        session.joinLobby(playerName = "Bob")
        yield()

        assertEquals(SessionRole.CLIENT, session.role.value)
        assertTrue(transport.discoveryStarted)
    }

    @Test
    fun `connectTo sends ClientHello after connected event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        yield()

        session.connectTo(endpointId = "host-ep")
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
    fun `host receives SeatChosen and updates lobby`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitIncoming("client-ep", NetMessage.SeatChosen(cell = 3))
        yield()

        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertEquals(3, bob?.seatCell)
    }

    @Test
    fun `host receives CharacterChosen and marks player ready`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("client-ep", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitIncoming("client-ep", NetMessage.CharacterChosen(element = Element.FIRE))
        yield()

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
        session.connectTo("host-ep")
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
        session.joinLobby(playerName = "Bob")
        session.connectTo("host-ep")
        yield()

        session.sessionEvents.test {
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
            yield()
            assertEquals(SessionEvent.RoundStarted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `client receives Rematch and emits RematchRequested event`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        session.connectTo("host-ep")
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
    fun `host chooseSeat updates lobby and does not send transport message`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        val countBefore = transport.sentMessages.size
        yield()

        session.chooseSeat(cell = 2)
        yield()

        val alice = session.lobby.value?.players?.find { it.name == "Alice" }
        assertEquals(2, alice?.seatCell)
        // Host does not send SeatChosen over transport — it mutates local state directly
        assertTrue(transport.sentMessages.drop(countBefore).none { it is NetMessage.SeatChosen })
    }

    @Test
    fun `client chooseSeat sends SeatChosen to host endpoint`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        yield()
        session.connectTo("host-ep")
        yield()

        session.chooseSeat(cell = 5)
        yield()

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
    fun `client receives host disconnection emits PeerLost`() = testScope.runTest {
        val session = buildSession()
        session.joinLobby(playerName = "Bob")
        yield()
        session.connectTo("host-ep")
        yield()

        session.sessionEvents.test {
            transport.emitConnection(ConnectionEvent.Disconnected("host-ep"))
            yield()
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

            session.chooseSeat(cell = 0)
            transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
    fun `startMatch throws when fewer than 2 players`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        session.chooseSeat(cell = 0)
        session.chooseCharacter(element = Element.FIRE)
        yield()

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            session.startMatch()
        }
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

            session.chooseSeat(cell = 0)
            transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
        session.joinLobby(playerName = "Bob")
        yield()
        session.connectTo("host-ep")
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
    fun `host rejects SeatChosen with out-of-range cell`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        val seatsBefore = session.lobby.value?.players?.map { it.seatCell }

        // Cell 9 is outside the valid 0..8 range — should be silently rejected.
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 9))
        yield()

        assertEquals(seatsBefore, session.lobby.value?.players?.map { it.seatCell })
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

        session.chooseSeat(cell = 0)
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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

        session.chooseSeat(cell = 0)
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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

        session.chooseSeat(cell = 0)
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
    fun `host rejects SeatChosen for already-taken cell`() = testScope.runTest {
        val session = buildSession()
        session.hostLobby(playerName = "Alice", mode = GameMode.STANDARD)
        yield()

        // Alice takes cell 2.
        session.chooseSeat(cell = 2)
        yield()

        // Bob joins and attempts to take the same cell — must be rejected.
        transport.emitIncoming("ep2", NetMessage.ClientHello("Bob"))
        yield()

        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 2))
        yield()

        // Bob's seat must still be null — the collision was rejected.
        val bob = session.lobby.value?.players?.find { it.name == "Bob" }
        assertNull(bob?.seatCell)
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

        session.chooseSeat(cell = 0)
        transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
            session.chooseSeat(cell = 0)
            transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
            session.chooseSeat(cell = 0)
            transport.emitIncoming("ep2", NetMessage.SeatChosen(cell = 1))
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
            session.joinLobby(playerName = "Bob")
            yield()
            session.connectTo("host-ep")
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
                    facingCaptureSeconds = 3,
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
