package com.justb81.compassduel.net

import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A host endpoint discovered during [startDiscovery].
 *
 * @param endpointId The Nearby Connections endpoint id used to connect.
 * @param name The host's display name (decoded from the advertised endpoint name).
 * @param mode The host's selected game mode, or null when the advertisement predates this
 *   metadata / could not be parsed.
 * @param playerCount Players already in the host's lobby (0 when unknown). See [AdvertisedLobby].
 */
data class DiscoveredEndpoint(
    val endpointId: String,
    val name: String,
    val mode: GameMode? = null,
    val playerCount: Int = 0,
)

/** Events emitted when a connection is established or dropped. */
sealed interface ConnectionEvent {
    /** A peer successfully connected. */
    data class Connected(val endpointId: String, val peerName: String) : ConnectionEvent

    /** A peer disconnected or was lost. */
    data class Disconnected(val endpointId: String) : ConnectionEvent
}

/**
 * A transport operation that failed and should be surfaced to the user.
 *
 * Emitted when a Play Services call throws synchronously or fails asynchronously, so the
 * UI can report the failure instead of the app crashing (or appearing to hang).
 */
enum class TransportError {
    /** [MessageTransport.startAdvertising] failed (host could not start hosting). */
    ADVERTISE,

    /** [MessageTransport.startDiscovery] failed (client could not search for hosts). */
    DISCOVER,

    /** [MessageTransport.requestConnection] failed (client could not reach the host). */
    CONNECT,
}

/**
 * Abstraction over the Nearby Connections transport so [com.justb81.compassduel.session.GameSession]
 * can be tested against an in-memory fake transport without Play Services.
 */
interface MessageTransport {

    /** Emits [ConnectionEvent] whenever a peer connects or disconnects. */
    val connectionEvents: SharedFlow<ConnectionEvent>

    /** Endpoints discovered during [startDiscovery]; cleared when discovery stops. */
    val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>>

    /** Decoded [NetMessage] payloads received from any endpoint. */
    val incomingMessages: SharedFlow<Pair<String, NetMessage>>

    /** Ids of all currently connected endpoints. */
    val connectedEndpointIds: StateFlow<Set<String>>

    /** Emits when a transport operation fails (e.g. Play Services throws). */
    val transportErrors: SharedFlow<TransportError>

    /**
     * Controls whether new inbound connections should be accepted.
     * Set to false by the host once a match is in progress or the lobby is full.
     */
    var acceptNewConnections: Boolean

    /** Start advertising so clients can discover this device. */
    fun startAdvertising(localName: String)

    /** Start scanning for advertising peers. */
    fun startDiscovery()

    /** Stop discovery scan (advertising continues). */
    fun stopDiscovery()

    /** Request a connection to [endpointId]. */
    fun requestConnection(endpointId: String, localName: String)

    /** Send [message] to a single [endpointId]. */
    fun send(endpointId: String, message: NetMessage)

    /** Send [message] to all currently connected endpoints. */
    fun broadcast(message: NetMessage)

    /**
     * Send [message] to a single [endpointId] reliably: the message is acknowledged by the
     * receiver and retransmitted until the ack arrives or the endpoint disconnects. Use for
     * control messages whose loss would strand a peer; high-frequency state uses [send].
     */
    fun sendReliable(endpointId: String, message: NetMessage)

    /**
     * Send [message] reliably to all currently connected endpoints (see [sendReliable]). Use
     * for control broadcasts ([NetMessage.RoundStart], [NetMessage.RoundEnd], …); the lossy
     * [broadcast] is for the high-frequency [NetMessage.StateBroadcast] stream.
     */
    fun broadcastReliable(message: NetMessage)

    /** Stop advertising, discovery, and all connections. */
    fun stopAll()
}
