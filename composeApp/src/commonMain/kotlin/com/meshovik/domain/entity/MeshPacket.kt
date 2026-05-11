package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

/**
 * Represents a raw BLE packet for transmission over the mesh network.
 * This is the wire format used for BLE characteristic read/write operations.
 */
@Serializable
data class MeshPacket(
    val packetId: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val ttl: Int,
    val hopCount: Int,
    val payload: ByteArray,
    val timestamp: Long
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
        return result
    }

    companion object {
        const val MAX_PAYLOAD_SIZE = 480 // BLE MTU is typically 512, leaving room for headers
    }
}
