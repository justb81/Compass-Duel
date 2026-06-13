package com.justb81.compassduel.net

import android.util.Log
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.justb81.compassduel.net.protocol.MessageCodec
import com.justb81.compassduel.net.protocol.NetMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nearby Connections transport implementation.
 *
 * Wraps [ConnectionsClient] (Play Services) and exposes the decoded message and
 * connection-event flows that [com.justb81.compassduel.session.GameSession] consumes.
 *
 * All connections are auto-accepted: v1 uses a trusted local group (players are
 * in the same room) and does not need pairing confirmation.
 *
 * This class is deliberately thin and not unit-tested; the untestable Play Services
 * callbacks are the only logic here. Session logic lives in GameSession which
 * uses the testable [MessageTransport] interface.
 */
@Singleton
class NearbyConnectionManager @Inject constructor(
    private val connectionsClient: ConnectionsClient,
) : MessageTransport {

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = BUFFER_CAPACITY)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    override val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, NetMessage>>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingMessages: SharedFlow<Pair<String, NetMessage>> = _incomingMessages.asSharedFlow()

    private val _connectedEndpointIds = MutableStateFlow<Set<String>>(emptySet())
    override val connectedEndpointIds: StateFlow<Set<String>> = _connectedEndpointIds.asStateFlow()

    /** Stores peer names captured in onConnectionInitiated so they can be emitted in onConnectionResult. */
    private val pendingPeerNames: MutableMap<String, String> = mutableMapOf()

    // ---------------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Store the peer name so it can be included in the Connected event
            pendingPeerNames[endpointId] = info.endpointName
            // Auto-accept — trusted local group in v1.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                _connectedEndpointIds.update { it + endpointId }
                val peerName = pendingPeerNames.remove(endpointId) ?: endpointId
                _connectionEvents.tryEmit(ConnectionEvent.Connected(endpointId, peerName))
            } else {
                pendingPeerNames.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingPeerNames.remove(endpointId)
            _connectedEndpointIds.update { it - endpointId }
            _connectionEvents.tryEmit(ConnectionEvent.Disconnected(endpointId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            _discoveredEndpoints.update { current ->
                if (current.none { it.endpointId == endpointId }) {
                    current + DiscoveredEndpoint(endpointId, info.endpointName)
                } else {
                    current
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredEndpoints.update { current ->
                current.filter { it.endpointId != endpointId }
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            try {
                val message = MessageCodec.decode(bytes)
                _incomingMessages.tryEmit(endpointId to message)
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.w(TAG, "Dropped malformed payload from $endpointId: ${e.message}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op: fire-and-forget; we do not track delivery confirmations.
        }
    }

    // ---------------------------------------------------------------------------
    // MessageTransport API
    // ---------------------------------------------------------------------------

    override fun startAdvertising(localName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient
            .startAdvertising(localName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnFailureListener { e -> Log.e(TAG, "startAdvertising failed: ${e.message}") }
    }

    override fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener { e -> Log.e(TAG, "startDiscovery failed: ${e.message}") }
    }

    override fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        _discoveredEndpoints.value = emptyList()
    }

    override fun requestConnection(endpointId: String, localName: String) {
        connectionsClient
            .requestConnection(localName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> Log.e(TAG, "requestConnection to $endpointId failed: ${e.message}") }
    }

    override fun send(endpointId: String, message: NetMessage) {
        val payload = Payload.fromBytes(MessageCodec.encode(message))
        connectionsClient.sendPayload(endpointId, payload)
    }

    override fun broadcast(message: NetMessage) {
        val payload = Payload.fromBytes(MessageCodec.encode(message))
        val ids = _connectedEndpointIds.value.toList()
        if (ids.isNotEmpty()) {
            connectionsClient.sendPayload(ids, payload)
        }
    }

    override fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        _connectedEndpointIds.value = emptySet()
        _discoveredEndpoints.value = emptyList()
        pendingPeerNames.clear()
    }

    companion object {
        private const val TAG = "NearbyConnectionManager"

        /** Nearby Connections service identifier — must match on host and client. */
        const val SERVICE_ID = "com.justb81.compassduel"

        /** Extra buffer capacity for shared flows carrying connection and message events. */
        private const val BUFFER_CAPACITY = 64
    }
}
