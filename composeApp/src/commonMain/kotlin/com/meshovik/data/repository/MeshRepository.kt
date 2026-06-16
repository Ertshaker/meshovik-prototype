package com.meshovik.data.repository

import com.meshovik.core.util.DeviceIdProvider
import com.meshovik.database.MeshovikDatabase
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
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
import kotlinx.coroutines.launch
import kotlin.time.Clock

class MeshRepository(
    private val database: MeshovikDatabase,
    private val deviceIdProvider: DeviceIdProvider
) {
    private val localDeviceId: String by lazy { deviceIdProvider.getDeviceId() }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // In-memory кэш — оставляем для совместимости
    private val _devices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val devices: StateFlow<List<MeshDevice>> = _devices.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _sentMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val sentMessages: StateFlow<List<MeshMessage>> = _sentMessages.asStateFlow()

    private val _contacts = MutableStateFlow<List<MeshDevice>>(emptyList())
    val contacts: StateFlow<List<MeshDevice>> = _contacts.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        repositoryScope.launch {
            try {
                // 1. Загружаем known peers (устройства)
                val profiles = database.meshovikQueries.getAllKnownPeers().executeAsList()

                val loadedDevices = profiles.map { profile ->
                    MeshDevice(
                        id = profile.mesh_id,
                        name = profile.display_name,
                        address = profile.mesh_id,
                        rssi = 0,
                        lastSeen = Clock.System.now(),
                        isOnline = false,
                        hopCount = 0,
                        meshId = profile.mesh_id,
                        wifiDirectAddress = null,
                        userName = profile.display_name
                    )
                }

                // 2. Загружаем чаты
                val conversations = database.meshovikQueries.getAllConversations().executeAsList()

                val loadedChats = conversations.map { conv ->
                    Chat(
                        id = conv.conversation_id,
                        type = if (conv.conversation_id == "broadcast") ChatType.BROADCAST else ChatType.DIRECT,
                        participantName = "Unknown",
                        participantAddress = conv.conversation_id,
                        lastMessage = null,
                        lastMessageTime = conv.updated_at
                    )
                }

                _chats.update { loadedChats }

                // ==================== ЗАГРУЗКА СООБЩЕНИЙ ====================
                val dbMessages = database.meshovikQueries.getAllMessages().executeAsList()

                val received = mutableListOf<MeshMessage>()
                val sent = mutableListOf<MeshMessage>()

                dbMessages.forEach { msg ->
                    val attachment = msg.attachment_id?.let { attId ->
                        database.meshovikQueries.getAttachment(attId).executeAsOneOrNull()?.let { att ->
                            Attachment(
                                id = att.attachment_id,
                                type = AttachmentType.IMAGE,
                                fileName = att.file_name,
                                mimeType = att.mime_type ?: "image/jpeg",
                                sizeBytes = att.size_bytes,
                                localUri = att.local_uri,
                                width = null,
                                height = null
                            )
                        }
                    }

                    val meshMessage = MeshMessage(
                        id = msg.message_id,
                        senderId = msg.sender_id,
                        receiverId = msg.receiver_id,
                        content = msg.content ?: "",
                        timestamp = msg.timestamp,
                        type = if (msg.type == "image") MessageType.ATTACHMENT else MessageType.TEXT,
                        attachment = attachment,
                        status = MeshMessageStatus.DELIVERED
                    )

                    if (msg.sender_id == localDeviceId) {
                        sent.add(meshMessage)
                    } else {
                        received.add(meshMessage)
                    }
                }
                val loadedContacts = profiles.map { profile ->
                    MeshDevice(
                        id = profile.mesh_id,
                        name = profile.display_name,
                        address = profile.mesh_id,
                        meshId = profile.mesh_id,
                        userName = profile.display_name,
                        lastSeen = Clock.System.now(), // можно взять из БД
                        isOnline = false,
                        rssi = 0,
                        hopCount = 0,
                        wifiDirectAddress = null
                    )
                }
                _contacts.update { loadedContacts }
                _messages.update { received }
                _sentMessages.update { sent }
                _devices.update { loadedDevices }
            } catch (e: Exception) {
            }
        }
    }

    // ====================== USER PROFILES ======================

    fun addOrUpdateContact(meshId: String, displayName: String) {
        repositoryScope.launch {
            try {
                val now = Clock.System.now().toEpochMilliseconds()

                database.meshovikQueries.insertOrReplaceKnownPeer(
                    mesh_id = meshId,
                    display_name = displayName,
                    last_seen = now,
                    last_name_update = now
                )

                // Создаём/обновляем объект MeshDevice
                val newContact = MeshDevice(
                    id = meshId,
                    name = displayName,
                    address = meshId,
                    meshId = meshId,
                    userName = displayName,
                    lastSeen = Clock.System.now(),
                    isOnline = false,
                    rssi = 0,
                    hopCount = 0,
                    wifiDirectAddress = null
                )

                // Обновляем список контактов
                _contacts.update { current ->
                    val index = current.indexOfFirst { it.meshId == meshId || it.address == meshId }
                    if (index >= 0) {
                        current.toMutableList().apply { this[index] = newContact }
                    } else {
                        current + newContact
                    }
                }

            } catch (e: Exception) {
            }
        }
    }

    fun isInContacts(meshId: String): Boolean {
        return _contacts.value.any { it.meshId == meshId || it.address == meshId }
    }

    fun addKnownPeer(meshId: String, displayName: String) {
        repositoryScope.launch {
            database.meshovikQueries.insertOrReplaceKnownPeer(
                mesh_id = meshId,
                display_name = displayName,
                last_seen = Clock.System.now().toEpochMilliseconds(),
                last_name_update = Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    fun deleteContact(meshId: String) {
        repositoryScope.launch {
            try {
                // Удаляем из базы
                database.meshovikQueries.deleteKnownPeer(meshId)

                // Обновляем in-memory список контактов
                _contacts.update { currentContacts ->
                    currentContacts.filterNot {
                        it.meshId == meshId || it.address == meshId
                    }
                }

                // Также обновляем _devices, если там хранятся контакты
                _devices.update { currentDevices ->
                    currentDevices.filterNot {
                        it.meshId == meshId || it.address == meshId
                    }
                }

            } catch (e: Exception) {
                throw e
            }
        }
    }

    fun getAllContacts(): List<MeshDevice> {
        return _devices.value
    }

    // ====================== DEVICES & CHATS ======================

    fun updateDevice(device: MeshDevice) {
        _devices.update { current ->
            val index = current.indexOfFirst { it.address == device.address }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = device }
            } else {
                current + device
            }
        }
    }

    fun updateChatFromDevice(device: MeshDevice) {
        _chats.update { chats ->
            val chatId = device.meshId.ifEmpty { device.address }
            val existingIndex = chats.indexOfFirst { it.id == chatId || it.id == device.address }

            val displayName = device.userName.ifBlank { device.name.ifBlank { device.meshId } }

            val updatedChat = Chat.fromDevice(device).copy(participantName = displayName)

            if (existingIndex >= 0) {
                chats.toMutableList().apply { this[existingIndex] = updatedChat }
            } else {
                chats + updatedChat
            }
        }
    }

    private fun updateChatLastMessage(chatId: String, lastMessage: String, timestamp: Long) {
        _chats.update { chats ->
            chats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(lastMessage = lastMessage, lastMessageTime = timestamp)
                } else chat
            }
        }

        repositoryScope.launch {
            database.meshovikQueries.insertOrReplaceConversation(
                conversation_id = chatId,
                last_message_id = null,
                updated_at = timestamp
            )
        }
    }

    // ====================== MESSAGES ======================

    fun addReceivedMessage(message: MeshMessage) {
        _messages.update { current ->
            if (current.any { it.id == message.id }) current else current + message
        }

        val chatId = if (message.receiverId == "BROADCAST") "broadcast" else message.senderId
        ensureChatExists(chatId, message.senderId)

        saveMessageToDb(message)
        updateChatLastMessage(chatId, message.content, message.timestamp)
    }
    private fun ensureChatExists(chatId: String, participantId: String) {
        val exists = _chats.value.any { it.id == chatId }
        if (exists) return

        val chat = if (chatId == "broadcast") {
            Chat.createBroadcastChat()
        } else {
            Chat(
                id = chatId,
                type = ChatType.DIRECT,
                participantName = "Unknown", // обновим позже через device
                participantAddress = participantId,
                lastMessage = null,
                lastMessageTime = 0,
                participantMeshId = "Unknown",
            )
        }

        _chats.update { it + chat }

        // Сохраняем в БД
        repositoryScope.launch {
            database.meshovikQueries.insertOrReplaceConversation(
                conversation_id = chatId,
                last_message_id = null,
                updated_at = Clock.System.now().toEpochMilliseconds()
            )
        }
    }
    fun addSentMessage(message: MeshMessage) {
        _sentMessages.update { current ->
            if (current.any { it.id == message.id }) current else current + message
        }

        val chatId = if (message.receiverId == "BROADCAST") "broadcast" else message.receiverId
        ensureChatExists(chatId, message.receiverId)

        saveMessageToDb(message)
        updateChatLastMessage(chatId, message.content, message.timestamp)
    }

    private fun saveMessageToDb(message: MeshMessage) {
        repositoryScope.launch {
            try {
                val attachmentId = message.attachment?.id

                // Сохраняем attachment, если есть
                message.attachment?.let { att ->
                    database.meshovikQueries.insertAttachment(
                        attachment_id = att.id,
                        message_id = message.id,
                        type = "image",
                        file_name = att.fileName,
                        mime_type = att.mimeType,
                        size_bytes = att.sizeBytes,
                        local_uri = att.localUri,
                        thumbnail_base64 = null
                    )
                }

                // Сохраняем сообщение
                val conversationId = when {
                    message.receiverId == "BROADCAST" -> "broadcast"
                    message.senderId == localDeviceId -> message.receiverId
                    else -> message.senderId
                }

                database.meshovikQueries.insertMessage(
                    message_id = message.id,
                    conversation_id = conversationId,
                    sender_id = message.senderId,
                    receiver_id = message.receiverId,
                    type = if (message.type == MessageType.ATTACHMENT) "image" else "text",
                    content = message.content,
                    attachment_id = attachmentId,
                    timestamp = message.timestamp
                )
            } catch (e: Exception) {
                throw e
            }
        }
    }

    // ====================== ОСТАЛЬНОЕ (без изменений) ======================

    fun getMessagesForChat(chatId: String, localDeviceAddress: String): List<MeshMessage> {
        val chat = _chats.value.find { it.id == chatId }
            ?: if (chatId == "broadcast") Chat.createBroadcastChat()
            else return emptyList()

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
                    val incoming = messages.filter { it.senderId == chatId }
                    val outgoing = sent.filter { it.senderId == localDeviceAddress }
                    incoming + outgoing
                }
            }.sortedBy { it.timestamp }
        }.onEach { result.value = it }.launchIn(repositoryScope)

        return result
    }

    fun updateAttachmentLocalUri(attachmentId: String, localUri: String) {
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.attachment?.id == attachmentId) {
                    msg.copy(attachment = msg.attachment.copy(localUri = localUri))
                } else msg
            }
        }
        _sentMessages.update { msgs ->
            msgs.map { msg ->
                if (msg.attachment?.id == attachmentId) {
                    msg.copy(attachment = msg.attachment.copy(localUri = localUri))
                } else msg
            }
        }

        repositoryScope.launch {
            database.meshovikQueries.updateAttachmentLocalUri(localUri, attachmentId)
        }
    }

    fun clear() {
        _devices.value = emptyList()
        _chats.value = emptyList()
        _messages.value = emptyList()
        _sentMessages.value = emptyList()

        repositoryScope.launch {
            database.meshovikQueries.deleteAllUsers()
            database.meshovikQueries.deleteAllMessages()
            database.meshovikQueries.deleteAllAttachments()
            database.meshovikQueries.deleteAllConversations()
        }
    }
}