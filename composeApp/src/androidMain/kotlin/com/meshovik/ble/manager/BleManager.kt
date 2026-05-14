package com.meshovik.ble.manager

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.RequiresPermission
import com.meshovik.ble.model.BleConstants
import com.meshovik.ble.scanner.BleScanner
import com.meshovik.ble.service.BleMeshService
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant
import timber.log.Timber
import java.util.UUID
import kotlin.time.Clock

/**
 * BLE Manager - orchestrates scanning, advertising, connections, and message routing.
 * This is the main entry point for the BLE mesh network functionality.
 */
class BleManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bleScanner = BleScanner(context)
    private val bleMeshService = BleMeshService(context)

    // State
    private val _discoveredDevices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshDevice>> = _discoveredDevices.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, BleMeshService.ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, BleMeshService.ConnectionState>> = _connectionStates.asStateFlow()

    // Local device info
    private var localDeviceAddress: String = ""
    private var localDeviceName: String = ""

    init {
        initializeLocalDeviceInfo()
        observeServiceEvents()
    }

    /**
     * Initializes local device info from Bluetooth adapter.
     */
    private fun initializeLocalDeviceInfo() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            localDeviceAddress = "Skibidi${(1..10).random()}"
            localDeviceName = adapter?.name ?: "Meshovik Device"
            Timber.i("Local device: $localDeviceName ($localDeviceAddress)")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLE permissions for device info")
            localDeviceAddress = "Skibidi${(1..10).random()}"
            localDeviceName = "Meshovik Device"
        }
    }

    /**
     * Observes events from the BLE mesh service.
     */
    private fun observeServiceEvents() {
        scope.launch {
            bleMeshService.connectionState.collectLatest { state ->
                _connectionStates.update { states ->
                    val address = when (state) {
                        is BleMeshService.ConnectionState.Connected -> state.address
                        is BleMeshService.ConnectionState.Disconnected -> state.address
                        is BleMeshService.ConnectionState.Connecting -> state.address
                    }
                    states + (address to state)
                }
            }
        }

        scope.launch {
            bleMeshService.receivedData.collectLatest { data ->
                val message = parseReceivedData(data)
                if (message != null) {
                    _receivedMessages.update { it + message }
                    Timber.i("Message received: ${message.id} from ${message.senderId}")
                }
            }
        }

        scope.launch {
            bleMeshService.advertisingState.collectLatest { isAdvertising ->
                _isAdvertising.value = isAdvertising
                if (isAdvertising) {
                    Timber.i("Advertising state: ON")
                } else {
                    Timber.w("Advertising state: OFF")
                }
            }
        }
    }

    /**
     * Starts the mesh service (GATT server + advertising).
     */
    fun startMeshService(): Flow<Boolean> {
        return bleMeshService.startService()
    }

    /**
     * Stops the mesh service.
     */
    fun stopMeshService() {
        bleMeshService.stopService()
        _isAdvertising.value = false
    }

    /**
     * Starts scanning for nearby mesh devices.
     */
    fun startScanning() {
        if (!bleScanner.isBleSupported()) {
            Timber.e("BLE not supported")
            return
        }
        if (!bleScanner.isBluetoothEnabled()) {
            Timber.e("Bluetooth not enabled")
            return
        }

        _isScanning.value = true
        scope.launch {
            bleScanner.scanForDevices().collectLatest { device ->
                // Update or add device
                _discoveredDevices.update { devices ->
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
        }
    }

    /**
     * Stops scanning for devices.
     */
    fun stopScanning() {
        bleScanner.stopScanning()
        _isScanning.value = false
    }

    /**
     * Sends a text message to a specific device.
     */
    fun sendMessage(targetAddress: String, content: String): MeshMessage {
        val messageId = UUID.randomUUID().toString().take(8)
        val message = MeshMessage(
            id = messageId,
            senderId = localDeviceAddress,
            receiverId = targetAddress,
            content = content,
            type = MessageType.TEXT,
            timestamp = Clock.System.now(),
            status = MeshMessageStatus.PENDING,
            ttl = BleConstants.DEFAULT_TTL,
            hopCount = 0
        )

        scope.launch {
            val packetData = bleMeshService.createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = content.toByteArray(Charsets.UTF_8)
            )

            val success = bleMeshService.sendData(targetAddress, packetData)
            if (success) {
                Timber.i("Message sent: $messageId to $targetAddress")
            } else {
                Timber.e("Failed to send message: $messageId to $targetAddress")
            }
        }

        return message
    }

    /**
     * Broadcasts a message to all discovered devices (flooding).
     */
    fun broadcastMessage(content: String): MeshMessage {
        val messageId = UUID.randomUUID().toString().take(8)
        val message = MeshMessage(
            id = messageId,
            senderId = localDeviceAddress,
            receiverId = "BROADCAST",
            content = content,
            type = MessageType.FLOOD,
            timestamp = Clock.System.now(),
            status = MeshMessageStatus.PENDING,
            ttl = BleConstants.DEFAULT_TTL,
            hopCount = 0
        )

        scope.launch {
            val packetData = bleMeshService.createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = content.toByteArray(Charsets.UTF_8)
            )

            // Send to all discovered devices
            _discoveredDevices.value.forEach { device ->
                if (device.address != localDeviceAddress) {
                    bleMeshService.sendData(device.address, packetData)
                }
            }
            Timber.i("Broadcast message sent: $messageId to ${_discoveredDevices.value.size} devices")
        }

        return message
    }

    /**
     * Parses received byte data into a MeshMessage.
     */
    private fun parseReceivedData(data: ByteArray): MeshMessage? {
        return try {
            val content = data.toString(Charsets.UTF_8)
            MeshMessage(
                id = UUID.randomUUID().toString().take(8),
                senderId = "unknown",
                receiverId = localDeviceAddress,
                content = content,
                type = MessageType.TEXT,
                timestamp = Clock.System.now(),
                status = MeshMessageStatus.DELIVERED,
                ttl = 0,
                hopCount = 0
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse received data")
            null
        }
    }

    /**
     * Gets the local device address.
     */
    fun getLocalAddress(): String = localDeviceAddress

    /**
     * Gets the local device name.
     */
    fun getLocalName(): String = localDeviceName

    /**
     * Cleans up resources.
     */
    fun cleanup() {
        stopScanning()
        stopMeshService()
    }
}
