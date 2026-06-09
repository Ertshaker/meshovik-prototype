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
            // Chat ID is meshId if known, otherwise BLE MAC address
            val chatId = device.meshId.ifEmpty { device.address }
            val existingIndex = chats.indexOfFirst { it.id == chatId || it.id == device.address }
            if (existingIndex >= 0) {
                chats.toMutableList().apply {
                    this[existingIndex] = chats[existingIndex].copy(
                        id = chatId,
                        participantAddress = chatId,
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

        combine(_messages, _sentMessages) { messages, sent ->
            when {
                chatId == "broadcast" -> {
                    (messages.filter { it.receiverId == "BROADCAST" } +
                            sent.filter { it.receiverId == "BROADCAST" })
                }
                else -> {
                    // Direct chat: messages between localDevice and chatId (participant's Mesh ID)
                    val incoming = messages.filter { msg ->
                        msg.senderId == chatId
                    }
                    val outgoing = sent.filter { msg ->
                        msg.senderId == localDeviceAddress
                    }
                    incoming + outgoing
                }
            }.sortedBy { it.timestamp }
        }.onEach { result.value = it }.launchIn(repositoryScope)

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
        _messages.update { current ->
            if (current.any { it.id == message.id }) current
            else current + message
        }

        val chatId = if (message.receiverId == "BROADCAST") "broadcast"
        else message.senderId

        updateChatLastMessage(chatId, message.content, message.timestamp)
    }

    fun addSentMessage(message: MeshMessage) {
        _sentMessages.update { current ->
            if (current.any { it.id == message.id }) current
            else current + message
        }

        val chatId = if (message.receiverId == "BROADCAST") "broadcast"
        else message.receiverId

        updateChatLastMessage(chatId, message.content, message.timestamp)
    }

    /**
     * Обновляет localUri вложения в сообщении после успешного получения файла.
     * Ищет сообщение по attachment.id в обоих списках (_messages и _sentMessages).
     */
    fun updateAttachmentLocalUri(attachmentId: String, localUri: String) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.attachment?.id == attachmentId) {
                    msg.copy(attachment = msg.attachment.copy(localUri = localUri))
                } else msg
            }
        }
        _sentMessages.update { messages ->
            messages.map { msg ->
                if (msg.attachment?.id == attachmentId) {
                    msg.copy(attachment = msg.attachment.copy(localUri = localUri))
                } else msg
            }
        }
    }

    fun clear() {
        _devices.value = emptyList()
        _chats.value = emptyList()
        _messages.value = emptyList()
        _sentMessages.value = emptyList()
    }
}
