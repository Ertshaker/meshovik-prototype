package com.meshovik.domain.entity

import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a chat conversation in the mesh network.
 */
@Serializable
data class Chat(
    val id: String,
    val type: ChatType,
    val participantAddress: String? = null,
    val participantName: String,
    val lastMessage: String? = null,
    @Contextual
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val rssi: Int = 0
) {
    companion object {
        fun createBroadcastChat(): Chat = Chat(
            id = "broadcast",
            type = ChatType.BROADCAST,
            participantName = "Broadcast"
        )

        fun fromDevice(device: MeshDevice): Chat = Chat(
            id = device.address,
            type = ChatType.DIRECT,
            participantAddress = device.address,
            participantName = device.name,
            rssi = device.rssi
        )
    }
}
