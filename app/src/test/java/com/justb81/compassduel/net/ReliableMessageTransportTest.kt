package com.justb81.compassduel.net

import app.cash.turbine.test
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * In-memory [MessageTransport] delegate that records outgoing payloads and lets a test inject
 * incoming messages and connection events — standing in for the real Nearby transport.
 */
private class FakeDelegate : MessageTransport {

    private val _incoming = MutableSharedFlow<Pair<String, NetMessage>>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingMessages: SharedFlow<Pair<String, NetMessage>> = _incoming

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    private val _connectedEndpointIds = MutableStateFlow<Set<String>>(emptySet())
    override val connectedEndpointIds: StateFlow<Set<String>> = _connectedEndpointIds

    override val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = MutableStateFlow(emptyList())
    override val transportErrors: SharedFlow<TransportError> = MutableSharedFlow()
    override var acceptNewConnections: Boolean = true

    /** Every (endpoint, message) handed to [send] / [broadcast], in order. */
    val sent: MutableList<Pair<String, NetMessage>> = mutableListOf()

    override fun send(endpointId: String, message: NetMessage) { sent += endpointId to message }
    override fun broadcast(message: NetMessage) { sent += BROADCAST to message }
    override fun sendReliable(endpointId: String, message: NetMessage) = send(endpointId, message)
    override fun broadcastReliable(message: NetMessage) = broadcast(message)
    override fun startAdvertising(localName: String) = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun requestConnection(endpointId: String, localName: String) = Unit
    override fun stopAll() = Unit

    fun connect(endpointId: String) { _connectedEndpointIds.value = _connectedEndpointIds.value + endpointId }

    suspend fun deliver(endpointId: String, message: NetMessage) { _incoming.emit(endpointId to message) }

    suspend fun disconnect(endpointId: String) {
        _connectedEndpointIds.value = _connectedEndpointIds.value - endpointId
        _connectionEvents.emit(ConnectionEvent.Disconnected(endpointId))
    }

    /** Reliable envelopes sent to [endpointId], in order. */
    fun reliableTo(endpointId: String): List<NetMessage.Reliable> =
        sent.filter { it.first == endpointId }.map { it.second }.filterIsInstance<NetMessage.Reliable>()

    /** Acks sent back to [endpointId]. */
    fun acksTo(endpointId: String): List<NetMessage.ControlAck> =
        sent.filter { it.first == endpointId }.map { it.second }.filterIsInstance<NetMessage.ControlAck>()

    companion object {
        private const val BUFFER = 64
        const val BROADCAST = "*"
    }
}

class ReliableMessageTransportTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val delegate = FakeDelegate()

    private fun build(scope: CoroutineScope): ReliableMessageTransport = ReliableMessageTransport(delegate, scope)

    private val controlMessage = NetMessage.LobbyState(
        mode = GameMode.STANDARD,
        players = emptyList(),
        yourPlayerId = 2,
    )

    @Test
    fun `sendReliable wraps the payload in a sequenced envelope`() = testScope.runTest {
        val transport = build(backgroundScope)
        delegate.connect("ep")

        transport.sendReliable("ep", controlMessage)
        yield()

        val envelopes = delegate.reliableTo("ep")
        assertEquals(1, envelopes.size)
        assertEquals(0L, envelopes.first().seq)
        assertEquals(controlMessage, envelopes.first().payload)
    }

    @Test
    fun `unacked control message is retransmitted until an ack arrives`() = testScope.runTest {
        val transport = build(backgroundScope)
        delegate.connect("ep")

        transport.sendReliable("ep", controlMessage)
        yield()
        assertEquals(1, delegate.reliableTo("ep").size)

        // Two retransmit intervals (300 ms each) without an ack → two more sends.
        delay(700)
        assertEquals(3, delegate.reliableTo("ep").size)

        // Acknowledge seq 0; retransmission must stop.
        delegate.deliver("ep", NetMessage.ControlAck(0L))
        yield()
        val countAtAck = delegate.reliableTo("ep").size
        delay(700)
        assertEquals(countAtAck, delegate.reliableTo("ep").size)
    }

    @Test
    fun `a pending control message is abandoned after the attempt cap`() = testScope.runTest {
        val transport = build(backgroundScope)
        delegate.connect("ep")

        transport.sendReliable("ep", controlMessage)
        // Well past MAX_ATTEMPTS (20) * 300 ms with no ack.
        delay(10_000)
        val count = delegate.reliableTo("ep").size
        delay(3_000)
        assertEquals(count, delegate.reliableTo("ep").size, "Retransmission must stop after the cap")
        assertTrue(count <= 21, "At most one initial send plus MAX_ATTEMPTS retransmits")
    }

    @Test
    fun `incoming reliable payload is delivered once and always acked`() = testScope.runTest {
        val transport = build(backgroundScope)
        val received = mutableListOf<Pair<String, NetMessage>>()
        backgroundScope.launch { transport.incomingMessages.collect { received += it } }
        yield()

        // The same envelope arrives twice (a retransmit); the inner payload is delivered once.
        // Each yield lets both the decorator's collector and the receive collector advance.
        delegate.deliver("ep", NetMessage.Reliable(5L, controlMessage))
        yield()
        yield()
        delegate.deliver("ep", NetMessage.Reliable(5L, controlMessage))
        yield()
        yield()

        assertEquals(listOf<Pair<String, NetMessage>>("ep" to controlMessage), received)
        // Both copies are acked so the sender stops retransmitting either way.
        assertEquals(listOf(5L, 5L), delegate.acksTo("ep").map { it.seq })
    }

    @Test
    fun `lossy traffic passes straight through without an envelope`() = testScope.runTest {
        val transport = build(backgroundScope)
        // Let the decorator's internal collector subscribe to the delegate before delivering.
        yield()
        val snapshot = NetMessage.PlayerMoved(playerId = 2, significant = true)

        transport.incomingMessages.test {
            transport.send("ep", snapshot)
            delegate.deliver("ep", snapshot)
            assertEquals(("ep" to snapshot), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The lossy send was forwarded verbatim — no Reliable envelope, no extra traffic.
        assertEquals(("ep" to snapshot), delegate.sent.single())
    }

    @Test
    fun `disconnect cancels pending retransmissions for that endpoint`() = testScope.runTest {
        val transport = build(backgroundScope)
        delegate.connect("ep")

        transport.sendReliable("ep", controlMessage)
        yield()
        assertEquals(1, delegate.reliableTo("ep").size)

        delegate.disconnect("ep")
        yield()
        delay(2_000)
        assertEquals(1, delegate.reliableTo("ep").size, "No retransmits after the endpoint dropped")
    }
}
