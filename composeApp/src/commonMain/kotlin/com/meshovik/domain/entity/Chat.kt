package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

@Serializable
data class Chat(
    val id: String,
    val type: ChatType,
    val participantAddress: String,
    val participantMeshId: String? = null,
    val participantName: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val rssi: Int = 0
) {

    companion object {
        fun createBroadcastChat(): Chat = Chat(
            id = "broadcast",
            type = ChatType.BROADCAST,
            participantAddress = "broadcast",
            participantName = "Всеобщий чат"
        )

        fun fromDevice(device: MeshDevice): Chat {
            val chatId = device.meshId.ifEmpty { device.address }

            return Chat(
                id = chatId,
                type = ChatType.DIRECT,
                participantAddress = device.address,           // BLE address для подключения
                participantMeshId = device.meshId.ifEmpty { null },
                participantName = device.userName,
                rssi = device.rssi
            )
        }
    }
}