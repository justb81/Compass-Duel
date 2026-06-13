package com.justb81.compassduel.net

import com.justb81.compassduel.net.protocol.NetMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [MessageTransport] decorator that adds reliable, idempotent delivery for control messages on
 * top of a best-effort [delegate] (the real [NearbyConnectionManager]).
 *
 * Nearby `BYTES` payloads are best-effort, and the high-frequency [NetMessage.StateBroadcast]
 * stream is intentionally lossy (the next snapshot supersedes a dropped one). Control messages
 * such as [NetMessage.RoundStart] / [NetMessage.RoundEnd] / [NetMessage.LobbyState] carry
 * one-shot state transitions that cannot be reconstructed, so [sendReliable] / [broadcastReliable]
 * wrap them in a [NetMessage.Reliable] envelope:
 *
 * - the sender assigns a per-endpoint monotonically increasing `seq`, keeps the payload in a
 *   pending set, and retransmits it every [RETRANSMIT_INTERVAL_MILLIS] until it is acknowledged
 *   (or [MAX_ATTEMPTS] is reached, or the endpoint disconnects);
 * - the receiver immediately returns a [NetMessage.ControlAck] for every envelope (so duplicates
 *   stop the retransmit) and delivers each `seq` to [incomingMessages] exactly once, dropping
 *   re-sends it has already seen.
 *
 * Lossy traffic ([send] / [broadcast]) and all non-message APIs pass straight through to
 * [delegate]; [NearbyConnectionManager] stays a thin, untested Play Services shim.
 *
 * The delivered-seq set per endpoint is bounded by the number of control messages exchanged in a
 * single local session, so it is left unpruned for simplicity.
 */
class ReliableMessageTransport(
    private val delegate: MessageTransport,
    private val scope: CoroutineScope,
) : MessageTransport {

    /** Per-endpoint reliability bookkeeping, guarded by [lock]. */
    private class EndpointState {
        var nextSeq: Long = 0L
        val pending: LinkedHashMap<Long, Pending> = LinkedHashMap()
        val delivered: MutableSet<Long> = mutableSetOf()
    }

    private class Pending(val payload: NetMessage, var attempts: Int = 0)

    private val lock = Any()
    private val endpoints: MutableMap<String, EndpointState> = mutableMapOf()

    private val _incomingMessages = MutableSharedFlow<Pair<String, NetMessage>>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingMessages: SharedFlow<Pair<String, NetMessage>> = _incomingMessages.asSharedFlow()

    // Pass-throughs to the underlying transport.
    override val connectionEvents: SharedFlow<ConnectionEvent> get() = delegate.connectionEvents
    override val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> get() = delegate.discoveredEndpoints
    override val connectedEndpointIds: StateFlow<Set<String>> get() = delegate.connectedEndpointIds
    override val transportErrors: SharedFlow<TransportError> get() = delegate.transportErrors

    override var acceptNewConnections: Boolean
        get() = delegate.acceptNewConnections
        set(value) { delegate.acceptNewConnections = value }

    init {
        scope.launch { delegate.incomingMessages.collect { (endpointId, message) -> onIncoming(endpointId, message) } }
        scope.launch { delegate.connectionEvents.collect { onConnectionEvent(it) } }
        scope.launch { retransmitLoop() }
    }

    // ---------------------------------------------------------------------------
    // Reliable send
    // ---------------------------------------------------------------------------

    override fun sendReliable(endpointId: String, message: NetMessage) {
        val seq = synchronized(lock) {
            val state = endpoints.getOrPut(endpointId) { EndpointState() }
            val assigned = state.nextSeq++
            state.pending[assigned] = Pending(message)
            assigned
        }
        delegate.send(endpointId, NetMessage.Reliable(seq, message))
    }

    override fun broadcastReliable(message: NetMessage) {
        delegate.connectedEndpointIds.value.forEach { endpointId -> sendReliable(endpointId, message) }
    }

    // ---------------------------------------------------------------------------
    // Lossy pass-throughs
    // ---------------------------------------------------------------------------

    override fun send(endpointId: String, message: NetMessage) = delegate.send(endpointId, message)

    override fun broadcast(message: NetMessage) = delegate.broadcast(message)

    override fun startAdvertising(localName: String) = delegate.startAdvertising(localName)

    override fun startDiscovery() = delegate.startDiscovery()

    override fun stopDiscovery() = delegate.stopDiscovery()

    override fun requestConnection(endpointId: String, localName: String) =
        delegate.requestConnection(endpointId, localName)

    override fun stopAll() {
        synchronized(lock) { endpoints.clear() }
        delegate.stopAll()
    }

    // ---------------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------------

    private fun onIncoming(endpointId: String, message: NetMessage) {
        when (message) {
            is NetMessage.Reliable -> {
                // Always ack — a duplicate re-send must still stop the sender retransmitting.
                delegate.send(endpointId, NetMessage.ControlAck(message.seq))
                val firstDelivery = synchronized(lock) {
                    endpoints.getOrPut(endpointId) { EndpointState() }.delivered.add(message.seq)
                }
                if (firstDelivery) _incomingMessages.tryEmit(endpointId to message.payload)
            }
            is NetMessage.ControlAck -> synchronized(lock) {
                endpoints[endpointId]?.pending?.remove(message.seq)
            }
            else -> _incomingMessages.tryEmit(endpointId to message)
        }
    }

    private fun onConnectionEvent(event: ConnectionEvent) {
        if (event is ConnectionEvent.Disconnected) {
            synchronized(lock) { endpoints.remove(event.endpointId) }
        }
    }

    private suspend fun retransmitLoop() {
        while (scope.isActive) {
            delay(RETRANSMIT_INTERVAL_MILLIS)
            collectRetransmissions().forEach { (endpointId, seq, payload) ->
                delegate.send(endpointId, NetMessage.Reliable(seq, payload))
            }
        }
    }

    /** Bumps attempt counts, drops payloads that exhausted [MAX_ATTEMPTS], and returns what to resend. */
    private fun collectRetransmissions(): List<Triple<String, Long, NetMessage>> = synchronized(lock) {
        val resend = mutableListOf<Triple<String, Long, NetMessage>>()
        endpoints.forEach { (endpointId, state) ->
            val exhausted = mutableListOf<Long>()
            state.pending.forEach { (seq, pending) ->
                pending.attempts++
                if (pending.attempts > MAX_ATTEMPTS) {
                    exhausted += seq
                } else {
                    resend += Triple(endpointId, seq, pending.payload)
                }
            }
            exhausted.forEach { state.pending.remove(it) }
        }
        resend
    }

    companion object {
        /** Extra buffer for the decorated incoming-message flow (matches the delegate). */
        private const val BUFFER_CAPACITY = 64

        /** How often pending (unacked) control messages are retransmitted. */
        private const val RETRANSMIT_INTERVAL_MILLIS = 300L

        /** Retransmit attempts before a pending control message is abandoned (~6 s at 300 ms). */
        private const val MAX_ATTEMPTS = 20
    }
}
