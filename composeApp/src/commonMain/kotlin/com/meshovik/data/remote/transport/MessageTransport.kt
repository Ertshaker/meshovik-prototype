package com.meshovik.data.remote.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface for message transport abstraction.
 * Allows sending and receiving messages through different transport channels (BLE, Wi-Fi Direct, etc.).
 */
interface MessageTransport {
    /**
     * The type of this transport.
     */
    val transportType: TransportType

    /**
     * Whether this transport is currently available and ready to use.
     */
    val isAvailable: Boolean

    /**
     * Flow of incoming messages from any connected peer.
     * Each emission contains the sender's ID and the raw payload bytes.
     */
    val incomingMessages: SharedFlow<IncomingMessage>

    /**
     * Flow of connection state changes.
     */
    val connectionState: SharedFlow<TransportConnectionState>

    /**
     * Sends data to a specific peer.
     *
     * @param peerId The identifier of the target peer.
     * @param payload The raw bytes to send.
     * @return TransportResult indicating success or failure.
     */
    suspend fun send(peerId: String, payload: ByteArray): TransportResult<Unit>

    /**
     * Broadcasts data to all connected peers.
     *
     * @param payload The raw bytes to broadcast.
     * @return TransportResult indicating success or failure.
     */
    suspend fun broadcast(payload: ByteArray): TransportResult<Unit>

    /**
     * Connects to a peer.
     *
     * @param peerId The identifier of the peer to connect to.
     * @return TransportResult indicating success or failure.
     */
    suspend fun connect(peerId: String): TransportResult<Unit>

    /**
     * Disconnects from a peer.
     *
     * @param peerId The identifier of the peer to disconnect from.
     */
    suspend fun disconnect(peerId: String)

    /**
     * Initializes the transport. Should be called before using.
     */
    suspend fun initialize(): TransportResult<Unit>

    /**
     * Shuts down the transport and releases resources.
     */
    fun shutdown()
}

/**
 * Represents an incoming message from a peer.
 */
data class IncomingMessage(
    val senderId: String,
    val payload: ByteArray,
    val transportType: TransportType,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as IncomingMessage
        if (senderId != other.senderId) return false
        if (!payload.contentEquals(other.payload)) return false
        if (transportType != other.transportType) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + transportType.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
