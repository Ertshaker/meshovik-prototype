package com.meshovik.data.repository

import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Repository for managing mesh devices, chats, and messages.
 * Acts as the single source of truth for mesh network data.
 */
class MeshRepository {
    private val _devices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val devices: StateFlow<List<MeshDevice>> = _devices.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _sentMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val sentMessages: StateFlow<List<MeshMessage>> = _sentMessages.asStateFlow()

    /**
     * Updates or adds a discovered device.
     */
    fun updateDevice(device: MeshDevice) {
        _devices.update { devices ->
            val existingIndex = devices.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                devices.toMutableList().apply {
                    this[existingIndex] = device
                }
            } else {
                devices + device
            }
        }
    }

    /**
     * Updates or creates a chat for the given device.
     */
    fun updateChatFromDevice(device: MeshDevice) {
        _chats.update { chats ->
            val existingIndex = chats.indexOfFirst { it.id == device.address }
            if (existingIndex >= 0) {
                chats.toMutableList().apply {
                    this[existingIndex] = chats[existingIndex].copy(
                        participantName = device.name,
                        rssi = device.rssi
                    )
                }
            } else {
                chats + Chat.fromDevice(device)
            }
        }
    }

    /**
     * Gets all chats including broadcast chat.
     */
    fun getChats(): StateFlow<List<Chat>> = _chats

    /**
     * Gets messages for a specific chat.
     * For BROADCAST: returns messages with receiverId = "BROADCAST"
     * For DIRECT: returns messages between localDevice and participantAddress
     */
    fun getMessagesForChat(chatId: String, localDeviceAddress: String): List<MeshMessage> {
        val chat = _chats.value.find { it.id == chatId } ?: return emptyList()

        return when (chat.type) {
            ChatType.BROADCAST -> {
                // All broadcast messages
                _messages.value.filter { it.receiverId == "BROADCAST" } +
                _sentMessages.value.filter { it.receiverId == "BROADCAST" }
            }
            ChatType.DIRECT -> {
                // Messages between localDevice and participantAddress
                val participantAddr = chat.participantAddress ?: return emptyList()
                _messages.value.filter {
                    (it.senderId == participantAddr && it.receiverId == localDeviceAddress) ||
                    (it.senderId == localDeviceAddress && it.receiverId == participantAddr)
                } + _sentMessages.value.filter {
                    (it.senderId == localDeviceAddress && it.receiverId == participantAddr)
                }
            }
        }.sortedBy { it.timestamp }
    }

    /**
     * Updates the last message in a chat.
     */
    fun updateChatLastMessage(chatId: String, message: String, timestamp: kotlin.time.Instant) {
        _chats.update { chats ->
            chats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(lastMessage = message, lastMessageTime = timestamp)
                } else {
                    chat
                }
            }
        }
    }

    /**
     * Adds a received message.
     */
    fun addReceivedMessage(message: MeshMessage) {
        _messages.update { it + message }
        // Update chat last message
        if (message.receiverId == "BROADCAST") {
            updateChatLastMessage("broadcast", message.content, message.timestamp)
        } else {
            message.senderId?.let { updateChatLastMessage(it, message.content, message.timestamp) }
        }
    }

    /**
     * Adds a sent message.
     */
    fun addSentMessage(message: MeshMessage) {
        _sentMessages.update { it + message }
        // Update chat last message
        if (message.receiverId == "BROADCAST") {
            updateChatLastMessage("broadcast", message.content, message.timestamp)
        } else {
            updateChatLastMessage(message.receiverId, message.content, message.timestamp)
        }
    }

    /**
     * Clears all data.
     */
    fun clear() {
        _devices.value = emptyList()
        _chats.value = emptyList()
        _messages.value = emptyList()
        _sentMessages.value = emptyList()
    }
}
