package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

@Serializable
data class Chat(
    val id: String,                    // Главный стабильный ID чата (MeshID или BLE address)
    val type: ChatType,
    val participantAddress: String,    // BLE address для соединения
    val participantMeshId: String? = null,   // ← Новый стабильный MeshID
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
            participantName = "Broadcast"
        )

        fun fromDevice(device: MeshDevice): Chat {
            val chatId = device.meshId.ifEmpty { device.address }

            return Chat(
                id = chatId,
                type = ChatType.DIRECT,
                participantAddress = device.address,           // BLE address для подключения
                participantMeshId = device.meshId.ifEmpty { null },
                participantName = device.name,
                rssi = device.rssi
            )
        }
    }
}