package com.meshovik.domain.entity

import com.meshovik.data.remote.transport.TransportType
import kotlinx.serialization.Serializable

/**
 * Represents a raw packet for transmission over the mesh network.
 * Transport-agnostic wire format that can be used with BLE, Wi-Fi Direct, etc.
 */
@Serializable
data class MeshPacket(
    val packetId: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val ttl: Int,
    val hopCount: Int,
    val payload: ByteArray,
    val timestamp: Long,
    /**
     * The transport this packet is intended for.
     * Used for routing and serialization decisions.
     */
    val transportType: TransportType? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MeshPacket
        if (packetId != other.packetId) return false
        if (sourceAddress != other.sourceAddress) return false
        if (destinationAddress != other.destinationAddress) return false
        if (ttl != other.ttl) return false
        if (hopCount != other.hopCount) return false
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        if (transportType != other.transportType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + sourceAddress.hashCode()
        result = 31 * result + destinationAddress.hashCode()
        result = 31 * result + ttl
        result = 31 * result + hopCount
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (transportType?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Default max payload size - conservative value for BLE compatibility.
         * Individual transports may support larger payloads.
         */
        const val MAX_PAYLOAD_SIZE = 480
    }
}
