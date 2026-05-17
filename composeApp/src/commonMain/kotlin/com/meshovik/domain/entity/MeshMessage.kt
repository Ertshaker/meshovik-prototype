package com.meshovik.domain.entity

import com.meshovik.data.remote.transport.TransportType
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Type of mesh message.
 */
@Serializable
enum class MessageType {
    TEXT,
    ACK,
    HEARTBEAT,
    FLOOD
}

/**
 * Represents a message in the mesh network.
 * Transport-agnostic - can be used with BLE, Wi-Fi Direct, or any other transport.
 */
@Serializable
data class MeshMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,

    @Contextual
    val timestamp: kotlin.time.Instant = Clock.System.now(),
    val status: MeshMessageStatus = MeshMessageStatus.PENDING,
    val ttl: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    /**
     * The transport used to send/receive this message.
     * Null if unknown or not applicable.
     */
    val transportType: TransportType? = null
) {
    companion object {
        const val DEFAULT_TTL = 5
    }
}
