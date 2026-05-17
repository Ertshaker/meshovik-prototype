package com.meshovik.data.repository

import com.meshovik.data.remote.transport.ConnectionManager
import com.meshovik.data.remote.transport.IncomingMessage
import com.meshovik.data.remote.transport.TransportResult
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Repository for managing mesh devices and messages.
 * Acts as the single source of truth for mesh network data.
 * Uses ConnectionManager for transport-agnostic communication.
 */
class MeshRepository(
    private val connectionManager: ConnectionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _devices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val devices: StateFlow<List<MeshDevice>> = _devices.asStateFlow()

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _sentMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val sentMessages: StateFlow<List<MeshMessage>> = _sentMessages.asStateFlow()

    init {
        observeIncomingMessages()
    }

    /**
     * Observes incoming messages from the ConnectionManager.
     */
    private fun observeIncomingMessages() {
        scope.launch {
            connectionManager.incomingMessages.collect { incoming ->
                val message = parseIncomingMessage(incoming)
                if (message != null) {
                    addReceivedMessage(message)
                    Timber.i("Message received via ${incoming.transportType}: ${message.id}")
                }
            }
        }
    }

    /**
     * Parses an IncomingMessage into a MeshMessage.
     */
    private fun parseIncomingMessage(incoming: IncomingMessage): MeshMessage? {
        return try {
            val content = incoming.payload.decodeToString()
            MeshMessage(
                id = "msg_${incoming.timestamp}",
                senderId = incoming.senderId,
                receiverId = "local",
                content = content,
                type = com.meshovik.domain.entity.MessageType.TEXT,
                status = MeshMessageStatus.DELIVERED,
                transportType = incoming.transportType
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse incoming message")
            null
        }
    }

    /**
     * Sends a message to a peer via the ConnectionManager.
     */
    suspend fun sendMessage(targetId: String, content: String): TransportResult<Unit> {
        val payload = content.toByteArray(Charsets.UTF_8)
        return connectionManager.send(targetId, payload)
    }

    /**
     * Broadcasts a message to all peers.
     */
    suspend fun broadcastMessage(content: String): TransportResult<Unit> {
        val payload = content.toByteArray(Charsets.UTF_8)
        return connectionManager.broadcast(payload)
    }

    /**
     * Updates or adds a discovered device.
     */
    fun updateDevice(device: MeshDevice) {
        _devices.update { devices ->
            val existingIndex = devices.indexOfFirst { it.id == device.id }
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
     * Adds a received message.
     */
    fun addReceivedMessage(message: MeshMessage) {
        _messages.update { it + message }
    }

    /**
     * Adds a sent message.
     */
    fun addSentMessage(message: MeshMessage) {
        _sentMessages.update { it + message }
    }

    /**
     * Gets a device by its ID.
     */
    fun getDevice(deviceId: String): MeshDevice? {
        return _devices.value.find { it.id == deviceId }
    }

    /**
     * Gets all online devices.
     */
    fun getOnlineDevices(): List<MeshDevice> {
        return _devices.value.filter { it.isOnline }
    }

    /**
     * Clears all data.
     */
    fun clear() {
        _devices.value = emptyList()
        _messages.value = emptyList()
        _sentMessages.value = emptyList()
    }
}
