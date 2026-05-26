package com.meshovik.presentation

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshovik.ble.manager.BleManager
import com.meshovik.data.repository.MeshRepository
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main ViewModel for the mesh messenger.
 * Coordinates BLE operations and UI state.
 */
class MeshViewModel(
    private val bleManager: BleManager,
    private val meshRepository: MeshRepository
) : ViewModel() {

    // Local device address for message filtering
    private val localDeviceAddress: String = bleManager.getLocalAddress()

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
        // Initialize local device address in UI state
        _uiState.update { it.copy(localDeviceAddress = localDeviceAddress) }
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
                devices.forEach { device ->
                    meshRepository.updateDevice(device)
                    meshRepository.updateChatFromDevice(device)
                }
                _uiState.update { it.copy(devices = devices) }
            }
        }
    }

    /**
     * Observes received messages from BLE manager.
     */
    private val processedMessageIds = mutableSetOf<String>()

    private fun observeMessages() {
        viewModelScope.launch {
            bleManager.receivedMessages.collect { messages ->
                // Добавляем только те, которых ещё нет
                messages.forEach { message ->
                    if (message.id !in processedMessageIds) {
                        processedMessageIds.add(message.id)
                        meshRepository.addReceivedMessage(message)
                    }
                }
                _uiState.update { it.copy(receivedMessages = messages) }
            }
        }
    }

    /**
     * Starts the mesh service (advertising + GATT server).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
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
     * Sends a message to a specific device.
     */
    fun sendMessage(targetAddress: String, content: String) {
        if (content.isBlank()) return

        val message = bleManager.sendMessage(targetAddress, content)
        meshRepository.addSentMessage(message)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + message)
        }

        viewModelScope.launch {
            _events.emit(MeshEvent.MessageSent(message))
        }
    }

    /**
     * Broadcasts a message to all discovered devices.
     */
    fun broadcastMessage(content: String) {
        if (content.isBlank()) return

        val message = bleManager.broadcastMessage(content)
        meshRepository.addSentMessage(message)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + message)
        }

        viewModelScope.launch {
            _events.emit(MeshEvent.MessageBroadcast(message))
        }
    }
    fun getMessagesFlowForChat(chatId: String): StateFlow<List<MeshMessage>> {
        return meshRepository.getMessagesFlowForChat(chatId, localDeviceAddress)
    }
    /**
     * Gets messages for a specific chat.
     */
    fun getMessagesForChat(chatId: String): List<MeshMessage> {
        return meshRepository.getMessagesForChat(chatId, localDeviceAddress)
    }

    /**
     * Подключается к выбранному устройству
     */
    fun connectToDevice(device: MeshDevice) {
        viewModelScope.launch {
            val bleDevice = bleManager.connectToDevice(device.address)

            if (bleDevice != null) {
                _uiState.update { it.copy(selectedDevice = device) }
            } else {
                _events.emit(MeshEvent.Error("Не удалось подключиться к ${device.name}"))
            }
        }
    }
    /**
     * Gets the local device info.
     */
    fun getLocalDeviceInfo(): Pair<String, String> {
        return bleManager.getLocalAddress() to bleManager.getLocalName()
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
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
    val connectionStates: Map<String, com.meshovik.ble.manager.BleManager.ConnectionState> = emptyMap(),
    val selectedDevice: MeshDevice? = null,
    val localDeviceAddress: String = ""
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
