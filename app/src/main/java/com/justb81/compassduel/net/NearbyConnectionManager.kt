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

    private val _transportErrors = MutableSharedFlow<TransportError>(extraBufferCapacity = BUFFER_CAPACITY)
    override val transportErrors: SharedFlow<TransportError> = _transportErrors.asSharedFlow()

    override var acceptNewConnections: Boolean = true

    /**
     * Stores peer names captured in onConnectionInitiated so they can be emitted in
     * onConnectionResult.
     *
     * Accessed from Play Services callback threads (onConnectionInitiated, onConnectionResult,
     * onDisconnected, stopAll), which may run concurrently. ConcurrentHashMap makes each
     * individual put/remove/get atomic without requiring explicit locking (#61).
     */
    private val pendingPeerNames: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    // ---------------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!acceptNewConnections) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
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

    // Play Services calls can throw synchronously (e.g. SecurityException / ApiException /
    // IllegalStateException from adapter state or OEM permission quirks) in addition to failing
    // asynchronously via the returned Task. We guard both paths so a transport failure surfaces
    // as a [TransportError] instead of crashing the app. Catching the broad boundary exception is
    // intentional here — this is a thin, untested Play Services shim (see class KDoc).

    @Suppress("TooGenericExceptionCaught")
    override fun startAdvertising(localName: String) {
        try {
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            connectionsClient
                .startAdvertising(localName, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnFailureListener { e -> reportFailure(TransportError.ADVERTISE, "startAdvertising", e) }
        } catch (e: Exception) {
            reportFailure(TransportError.ADVERTISE, "startAdvertising", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun startDiscovery() {
        try {
            val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            connectionsClient
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnFailureListener { e -> reportFailure(TransportError.DISCOVER, "startDiscovery", e) }
        } catch (e: Exception) {
            reportFailure(TransportError.DISCOVER, "startDiscovery", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stopDiscovery() {
        try {
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "stopDiscovery failed", e)
        }
        _discoveredEndpoints.value = emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun requestConnection(endpointId: String, localName: String) {
        try {
            connectionsClient
                .requestConnection(localName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e -> reportFailure(TransportError.CONNECT, "requestConnection to $endpointId", e) }
        } catch (e: Exception) {
            reportFailure(TransportError.CONNECT, "requestConnection to $endpointId", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun send(endpointId: String, message: NetMessage) {
        try {
            val payload = Payload.fromBytes(MessageCodec.encode(message))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            // Log-only: per-message failures are too granular to surface in the UI.
            Log.e(TAG, "send to $endpointId failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun broadcast(message: NetMessage) {
        try {
            val payload = Payload.fromBytes(MessageCodec.encode(message))
            val ids = _connectedEndpointIds.value.toList()
            if (ids.isNotEmpty()) {
                connectionsClient.sendPayload(ids, payload)
            }
        } catch (e: Exception) {
            // Log-only: per-message failures are too granular to surface in the UI.
            Log.e(TAG, "broadcast failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stopAll() {
        try {
            connectionsClient.stopAllEndpoints()
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "stopAll failed", e)
        }
        _connectedEndpointIds.value = emptySet()
        _discoveredEndpoints.value = emptyList()
        pendingPeerNames.clear()
    }

    private fun reportFailure(error: TransportError, operation: String, cause: Throwable) {
        Log.e(TAG, "$operation failed: ${cause.message}", cause)
        _transportErrors.tryEmit(error)
    }

    companion object {
        private const val TAG = "NearbyConnectionManager"

        /** Nearby Connections service identifier — must match on host and client. */
        const val SERVICE_ID = "com.justb81.compassduel"

        /** Extra buffer capacity for shared flows carrying connection and message events. */
        private const val BUFFER_CAPACITY = 64
    }
}
