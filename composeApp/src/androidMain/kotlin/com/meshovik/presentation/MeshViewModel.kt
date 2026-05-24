package com.meshovik.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshovik.BleAdvertiser
import com.meshovik.BleDevice
import com.meshovik.BleScanner
import com.meshovik.ble.manager.BleManager
import com.meshovik.data.repository.MeshRepository
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main ViewModel for the mesh messenger.
 * Coordinates BLE operations and UI state.
 */
class MeshViewModel(
    private val bleManager: BleManager,
    private val meshRepository: MeshRepository,
    private val scanner: BleScanner,
    private val advertiser: BleAdvertiser
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // UI State
    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var device: BleDevice? = null
    private var scanJob: Job? = null
    private var observeJob: Job? = null

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
     * Observes received messages from BLE manager.
     */
    private fun observeMessages() {
        viewModelScope.launch {
            bleManager.receivedMessages.collect { messages ->
                messages.forEach { meshRepository.addReceivedMessage(it) }
                _uiState.update { it.copy(receivedMessages = messages) }
            }
        }

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
        if (_isScanning.value) return

        _isScanning.value = true

        scanJob = scope.launch {
            scanner.scan().collect { adv ->
                bleManager.discoveredDevices.update { it + adv }
            }
        }
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
