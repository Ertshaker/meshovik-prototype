package com.meshovik.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshovik.ble.manager.BleManager
import com.meshovik.data.remote.transport.ConnectionManager
import com.meshovik.data.remote.transport.TransportResult
import com.meshovik.data.repository.MeshRepository
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Clock

/**
 * Main ViewModel for the mesh messenger.
 * Coordinates BLE operations and UI state.
 * Uses transport-agnostic repository for message operations.
 */
class MeshViewModel(
    private val bleManager: BleManager,
    private val meshRepository: MeshRepository
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<MeshEvent>()
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    init {
        observeBleState()
        observeDevices()
        observeMessages()
    }

    /**
     * Observes BLE manager state changes.
     */
    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.isScanning.collect { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }

        viewModelScope.launch {
            bleManager.isAdvertising.collect { isAdvertising ->
                _uiState.update { it.copy(isAdvertising = isAdvertising) }
            }
        }

        viewModelScope.launch {
            bleManager.connectionStates.collect { states ->
                _uiState.update { it.copy(connectionStates = states) }
            }
        }
    }

    /**
     * Observes discovered devices from BLE manager and syncs to repository.
     */
    private fun observeDevices() {
        viewModelScope.launch {
            bleManager.discoveredDevices.collect { devices ->
                devices.forEach { meshRepository.updateDevice(it) }
                _uiState.update { it.copy(devices = devices) }
            }
        }
    }

    /**
     * Observes received messages from repository (transport-agnostic).
     */
    private fun observeMessages() {
        viewModelScope.launch {
            meshRepository.messages.collect { messages ->
                _uiState.update { it.copy(receivedMessages = messages) }
            }
        }
    }

    /**
     * Starts the mesh service (advertising + GATT server).
     */
    fun startMeshService() {
        viewModelScope.launch {
            bleManager.startMeshService().collect { success ->
                if (success) {
                    Timber.i("Mesh service started successfully")
                    _events.emit(MeshEvent.ServiceStarted)
                } else {
                    Timber.e("Failed to start mesh service")
                    _events.emit(MeshEvent.Error("Failed to start mesh service"))
                }
            }
        }
    }

    /**
     * Stops the mesh service.
     */
    fun stopMeshService() {
        bleManager.stopMeshService()
    }

    /**
     * Starts scanning for nearby devices.
     */
    fun startScanning() {
        bleManager.startScanning()
    }

    /**
     * Stops scanning.
     */
    fun stopScanning() {
        bleManager.stopScanning()
    }

    /**
     * Sends a message to a specific device via the transport layer.
     */
    fun sendMessage(targetAddress: String, content: String) {
        if (content.isBlank()) return

        val messageId = targetAddress.take(4) + "_" + System.currentTimeMillis().toString(16).takeLast(4)
        val sentMessage = MeshMessage(
            id = messageId,
            senderId = bleManager.getLocalAddress(),
            receiverId = targetAddress,
            content = content,
            type = MessageType.TEXT,
            timestamp = Clock.System.now(),
            status = MeshMessageStatus.SENT
        )

        meshRepository.addSentMessage(sentMessage)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + sentMessage)
        }

        viewModelScope.launch {
            val result = meshRepository.sendMessage(targetAddress, content)
            when (result) {
                is TransportResult.Success -> {
                    Timber.i("Message sent via transport: $messageId")
                    _events.emit(MeshEvent.MessageSent(sentMessage))
                }
                is TransportResult.Error -> {
                    Timber.e("Failed to send message: ${result.message}")
                    _events.emit(MeshEvent.Error("Failed to send: ${result.message}"))
                }
                is TransportResult.InProgress -> {
                    Timber.d("Message sending in progress: $messageId")
                }
            }
        }
    }

    /**
     * Broadcasts a message to all discovered devices via the transport layer.
     */
    fun broadcastMessage(content: String) {
        if (content.isBlank()) return

        val messageId = "broadcast_" + System.currentTimeMillis().toString(16).takeLast(4)
        val sentMessage = MeshMessage(
            id = messageId,
            senderId = bleManager.getLocalAddress(),
            receiverId = "BROADCAST",
            content = content,
            type = MessageType.FLOOD,
            timestamp = Clock.System.now(),
            status = MeshMessageStatus.SENT
        )

        meshRepository.addSentMessage(sentMessage)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + sentMessage)
        }

        viewModelScope.launch {
            val result = meshRepository.broadcastMessage(content)
            when (result) {
                is TransportResult.Success -> {
                    Timber.i("Broadcast sent via transport: $messageId")
                    _events.emit(MeshEvent.MessageBroadcast(sentMessage))
                }
                is TransportResult.Error -> {
                    Timber.e("Failed to broadcast: ${result.message}")
                    _events.emit(MeshEvent.Error("Failed to broadcast: ${result.message}"))
                }
                is TransportResult.InProgress -> {
                    Timber.d("Broadcast sending in progress: $messageId")
                }
            }
        }
    }

    /**
     * Gets the local device info.
     */
    fun getLocalDeviceInfo(): Pair<String, String> {
        return bleManager.getLocalAddress() to bleManager.getLocalName()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.cleanup()
    }
}

/**
 * UI state for the mesh messenger.
 */
data class MeshUiState(
    val devices: List<MeshDevice> = emptyList(),
    val receivedMessages: List<MeshMessage> = emptyList(),
    val sentMessages: List<MeshMessage> = emptyList(),
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val connectionStates: Map<String, com.meshovik.ble.service.BleMeshService.ConnectionState> = emptyMap(),
    val selectedDevice: MeshDevice? = null
)

/**
 * UI events for the mesh messenger.
 */
sealed class MeshEvent {
    data object ServiceStarted : MeshEvent()
    data class MessageSent(val message: MeshMessage) : MeshEvent()
    data class MessageBroadcast(val message: MeshMessage) : MeshEvent()
    data class Error(val message: String) : MeshEvent()
}
