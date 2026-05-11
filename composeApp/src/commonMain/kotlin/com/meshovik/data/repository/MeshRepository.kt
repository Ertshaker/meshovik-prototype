package com.meshovik.data.repository

import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Repository for managing mesh devices and messages.
 * Acts as the single source of truth for mesh network data.
 */
class MeshRepository {
    private val _devices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val devices: StateFlow<List<MeshDevice>> = _devices.asStateFlow()

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _sentMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val sentMessages: StateFlow<List<MeshMessage>> = _sentMessages.asStateFlow()

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
