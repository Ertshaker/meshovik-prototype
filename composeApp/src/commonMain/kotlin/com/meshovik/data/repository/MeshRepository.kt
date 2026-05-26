package com.meshovik.data.repository

import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Repository for managing mesh devices, chats, and messages.
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
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun updateDevice(device: MeshDevice) {
        _devices.update { devices ->
            val existingIndex = devices.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                devices.toMutableList().apply { this[existingIndex] = device }
            } else {
                devices + device
            }
        }
    }

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

    fun getMessagesForChat(chatId: String, localDeviceAddress: String): List<MeshMessage> {
        val chat = _chats.value.find { it.id == chatId }
            ?: if (chatId == "broadcast") Chat.createBroadcastChat()
            else return emptyList()
        print(chat)

        return when (chat.type) {
            ChatType.BROADCAST -> {
                _messages.value.filter { it.receiverId == "BROADCAST" } +
                _sentMessages.value.filter { it.receiverId == "BROADCAST" }
            }
            ChatType.DIRECT -> {
                val participantAddr = chat.participantAddress ?: return emptyList()
                _messages.value.filter {
                    (it.senderId == participantAddr && it.receiverId == localDeviceAddress) ||
                    (it.senderId == localDeviceAddress && it.receiverId == participantAddr)
                } + _sentMessages.value.filter {
                    it.senderId == localDeviceAddress && it.receiverId == participantAddr
                }
            }
        }.sortedBy { it.timestamp }
    }
    fun getMessagesFlowForChat(
        chatId: String,
        localDeviceAddress: String
    ): StateFlow<List<MeshMessage>> {
        val result = MutableStateFlow<List<MeshMessage>>(emptyList())

        combine(_messages, _sentMessages, _chats) { messages, sent, chats ->
            val chat = chats.find { it.id == chatId }
                ?: if (chatId == "broadcast") Chat.createBroadcastChat()
                else return@combine emptyList()

            when (chat.type) {
                ChatType.BROADCAST -> {
                    (messages.filter { it.receiverId == "BROADCAST" } +
                            sent.filter { it.receiverId == "BROADCAST" })
                }
                ChatType.DIRECT -> {
                    val participant = chat.participantAddress ?: return@combine emptyList()
                    (messages.filter {
                        (it.senderId == participant && it.receiverId == localDeviceAddress) ||
                                (it.senderId == localDeviceAddress && it.receiverId == participant)
                    } +
                            sent.filter {
                                it.senderId == localDeviceAddress && it.receiverId == participant
                            })
                }
            }.sortedBy { it.timestamp }
        }.onEach {
            result.value = it
        }.launchIn(repositoryScope)  // ← единый scope

        return result
    }

    fun updateChatLastMessage(chatId: String, message: String, timestamp: Long) {
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

    fun addReceivedMessage(message: MeshMessage) {
        _messages.update { messages ->
            if (messages.any { it.id == message.id }) {
                messages  // ← уже есть, не добавляем
            } else {
                messages + message
            }
        }
        if (message.receiverId == "BROADCAST") {
            updateChatLastMessage("broadcast", message.content, message.timestamp)
        } else {
            updateChatLastMessage(message.senderId, message.content, message.timestamp)
        }
    }

    fun addSentMessage(message: MeshMessage) {
        _sentMessages.update { messages ->
            if (messages.any { it.id == message.id }) {
                messages
            } else {
                messages + message
            }
        }
        if (message.receiverId == "BROADCAST") {
            updateChatLastMessage("broadcast", message.content, message.timestamp)
        } else {
            updateChatLastMessage(message.receiverId, message.content, message.timestamp)
        }
    }

    fun clear() {
        _devices.value = emptyList()
        _chats.value = emptyList()
        _messages.value = emptyList()
        _sentMessages.value = emptyList()
    }
}
