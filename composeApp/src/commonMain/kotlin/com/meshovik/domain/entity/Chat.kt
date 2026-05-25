package com.meshovik.domain.entity

import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a chat conversation in the mesh network.
 * Can be either a broadcast chat or a direct chat with a specific device.
 */
@Serializable
data class Chat(
    /**
     * Unique chat identifier.
     * For BROADCAST: "broadcast"
     * For DIRECT: device address
     */
    val id: String,

    /**
     * Type of this chat (broadcast or direct).
     */
    val type: ChatType,

    /**
     * Device address of the chat participant (for DIRECT chats).
     * Null for BROADCAST chat.
     */
    val participantAddress: String? = null,

    /**
     * Display name of the participant (for DIRECT chats).
     * "Broadcast" for BROADCAST chat.
     */
    val participantName: String,

    /**
     * Content of the last message in this chat.
     */
    val lastMessage: String? = null,

    /**
     * Timestamp of the last message.
     */
    @Contextual
    val lastMessageTime: Instant? = null,

    /**
     * Number of unread messages in this chat.
     */
    val unreadCount: Int = 0,

    /**
     * RSSI signal strength of the participant device.
     * 0 for BROADCAST chat.
     */
    val rssi: Int = 0
) {
    companion object {
        /**
         * Creates a broadcast chat instance.
         */
        fun createBroadcastChat(): Chat = Chat(
            id = "broadcast",
            type = ChatType.BROADCAST,
            participantName = "Broadcast"
        )

        /**
         * Creates a direct chat instance from a MeshDevice.
         */
        fun fromDevice(device: MeshDevice): Chat = Chat(
            id = device.address,
            type = ChatType.DIRECT,
            participantAddress = device.address,
            participantName = device.name,
            rssi = device.rssi
        )
    }
}
