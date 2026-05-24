package com.meshovik.ble

import com.juul.kable.Peripheral
import com.meshovik.core.util.Logger
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)

/**
 * BLE Manager - orchestrates scanning, advertising, connections, and message routing.
 * This is the main entry point for the BLE mesh network functionality.
 * Uses platform-agnostic abstractions for multiplatform support.
 */
class BleManager(
    private val bleScanner: BleScanner,
    private val bleCentral: BleCentral,
    private val blePeripheral: BlePeripheral,
    /**
     * Stable local device address. Should be provided by platform-specific code
     * (e.g., Android ID on Android, identifierForVendor on iOS).
     * Falls back to a random UUID if not provided.
     */
    localDeviceAddressOverride: String? = null,
    localDeviceNameOverride: String? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _discoveredDevices = MutableStateFlow<List<Peripheral>>(emptyList())
    val discoveredDevices: StateFlow<List<Peripheral>> = _discoveredDevices.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    // Local device info - stable identifiers
    private var localDeviceAddress: String = localDeviceAddressOverride ?: Uuid.random().toString().take(12)
    private var localDeviceName: String = localDeviceNameOverride ?: "Meshovik Device"

    // Track scanning timeout job to cancel it if needed
    private var scanningTimeoutJob: kotlinx.coroutines.Job? = null

    // Deduplication cache: packetId -> timestamp
    private val seenPackets = mutableMapOf<String, Long>()

    init {
        observeServiceEvents()
        observeCentralConnectionStates()
    }

    /**
     * Observes connection states from BleCentral.
     */
    private fun observeCentralConnectionStates() {
        scope.launch {
            bleCentral.connectionState.collectLatest { state ->
                val address = when (state) {
                    is CentralConnectionState.Connecting -> state.address
                    is CentralConnectionState.Connected -> state.address
                    is CentralConnectionState.AlreadyConnected -> state.address
                    is CentralConnectionState.Disconnecting -> state.address
                    is CentralConnectionState.Disconnected -> state.address
                    is CentralConnectionState.Error -> state.address
                }
                val connectionState = when (state) {
                    is CentralConnectionState.Connecting -> ConnectionState.Connecting(address)
                    is CentralConnectionState.Connected -> ConnectionState.Connected(address)
                    is CentralConnectionState.AlreadyConnected -> ConnectionState.Connected(address)
                    is CentralConnectionState.Disconnecting -> ConnectionState.Connecting(address)
                    is CentralConnectionState.Disconnected -> ConnectionState.Disconnected(address)
                    is CentralConnectionState.Error -> ConnectionState.Disconnected(address)
                }
                _connectionStates.update { states ->
                    states + (address to connectionState)
                }
            }
        }

        // NOTE: bleCentral.receivedData is currently unused.
        // All incoming data arrives via blePeripheral.receivedData (GATT server).
        // If Central-side notifications are needed in the future, add a collector here.
    }

    /**
     * Observes events from the BLE mesh service.
     */
    private fun observeServiceEvents() {
        scope.launch {
            blePeripheral.connectionState.collectLatest { state ->
                _connectionStates.update { states ->
                    val address = when (state) {
                        is ConnectionState.Connected -> state.address
                        is ConnectionState.Disconnected -> state.address
                        is ConnectionState.Connecting -> state.address
                    }
                    states + (address to state)
                }
            }
        }

        scope.launch {
            blePeripheral.receivedData.collectLatest { received ->
                val message = parseReceivedData(received.data, received.sourceAddress)
                if (message != null) {
                    _receivedMessages.update { it + message }
                    Logger.i("BleManager", "Message received via Peripheral: ${message.id} from ${message.senderId}")
                }
            }
        }

        scope.launch {
            blePeripheral.advertisingState.collectLatest { isAdvertising ->
                _isAdvertising.value = isAdvertising
                if (isAdvertising) {
                    Logger.i("BleManager", "Advertising state: ON")
                } else {
                    Logger.i("BleManager", "Advertising state: OFF")
                }
            }
        }
    }

    /**
     * Starts the mesh service (GATT server + advertising).
     */
    fun startMeshService(): Flow<Boolean> {
        return blePeripheral.startService()
    }

    /**
     * Stops the mesh service.
     */
    fun stopMeshService() {
        blePeripheral.stopService()
        _isAdvertising.value = false
    }

    /**
     * Starts scanning for nearby mesh devices.
     */
    fun startScanning() {
        if (!bleScanner.isBleSupported()) {
            Logger.w("BleManager", "BLE not supported")
            return
        }
        if (!bleScanner.isBluetoothEnabled()) {
            Logger.w("BleManager", "Bluetooth not enabled")
            return
        }

        // Cancel any existing scanning timeout
        scanningTimeoutJob?.cancel()

        _isScanning.value = true
        scope.launch {
            bleScanner.scanForDevices().collectLatest { device ->
                // Update or add device
                _discoveredDevices.update { devices ->
                    val existingIndex = devices.indexOfFirst { it.identifier == device.identifier }
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

        // Auto-stop scanning after timeout
        scanningTimeoutJob = scope.launch {
            delay(BleConstants.SCAN_DURATION_MS)
            stopScanning()
            Logger.i("BleManager", "Scanning stopped after ${BleConstants.SCAN_DURATION_MS / 1000}s timeout")
        }
    }

    /**
     * Stops scanning for devices.
     */
    fun stopScanning() {
        // Cancel the scanning timeout job if it's running
        scanningTimeoutJob?.cancel()
        scanningTimeoutJob = null

        bleScanner.stopScanning()
        _isScanning.value = false
    }

    /**
     * Sends a text message to a specific device via BleCentral.
     */
    fun sendMessage(targetAddress: String, content: String): MeshMessage {
        Logger.i("BleManager", "sendMessage() called: target=$targetAddress, content='$content'")
        val messageId = Uuid.random().toString().take(8)
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
            try {
                Logger.d("BleManager", "Creating mesh packet for message: $messageId")
                val packetData = blePeripheral.createMeshPacket(
                    packetId = messageId,
                    ttl = message.ttl,
                    hopCount = message.hopCount,
                    payload = content.encodeToByteArray()
                )
                Logger.d("BleManager", "Packet created: ${packetData.size} bytes")

                // Connect and write via BleCentral (Kable)
                Logger.d("BleManager", "Calling connectAndWrite for $targetAddress")
                val peripheralDevice = getPeripheral(address = targetAddress)

                val success = bleCentral.connectAndWrite(peripheralDevice!!, packetData)
                if (success) {
                    Logger.i("BleManager", "Message sent: $messageId to $targetAddress")
                } else {
                    Logger.w("BleManager", "Failed to send message: $messageId to $targetAddress (connectAndWrite returned false)")
                }
            } catch (e: Exception) {
                Logger.e("BleManager", "Exception in sendMessage coroutine for $messageId", e)
            }
        }

        return message
    }

    /**
     * Broadcasts a message to all discovered devices (flooding).
     */
    fun broadcastMessage(content: String): MeshMessage {
        Logger.i("BleManager", "broadcastMessage() called: content='$content'")
        val messageId = Uuid.random().toString().take(8)
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
            try {
                Logger.d("BleManager", "Creating mesh packet for broadcast: $messageId")
                val packetData = blePeripheral.createMeshPacket(
                    packetId = messageId,
                    ttl = message.ttl,
                    hopCount = message.hopCount,
                    payload = content.encodeToByteArray()
                )
                Logger.d("BleManager", "Broadcast packet created: ${packetData.size} bytes")

                // Connect and write to all discovered devices via BleCentral
                val devices = _discoveredDevices.value.filter { it.identifier.toString() != localDeviceAddress }
                Logger.d("BleManager", "Broadcasting to ${devices.size} devices")
                devices.forEach { device ->
                    Logger.d("BleManager", "Broadcasting to device: ${device.identifier.toString()}")
                    bleCentral.connectAndWrite(device, packetData)
                }
                Logger.i("BleManager", "Broadcast message sent: $messageId to ${devices.size} devices")
            } catch (e: Exception) {
                Logger.e("BleManager", "Exception in broadcastMessage coroutine for $messageId", e)
            }
        }

        return message
    }

    /**
     * Parses received byte data into a MeshMessage.
     * Handles deduplication and mesh packet header parsing.
     */
    private fun parseReceivedData(data: ByteArray, sourceAddress: String): MeshMessage? {
        if (data.size < 10) {
            Logger.w("BleManager", "Received packet too small: ${data.size} bytes")
            return null
        }

        // Extract packet ID (first 8 bytes as hex string)
        val packetId = data.sliceArray(0 until 8).joinToString("")

        // Check for duplicate
        val now = Clock.System.now().toEpochMilliseconds()
        if (seenPackets.containsKey(packetId)) {
            Logger.d("BleManager", "Duplicate packet ignored: $packetId")
            return null
        }

        // Add to seen packets and clean old entries
        seenPackets[packetId] = now
        cleanDeduplicationCache()

        // Extract TTL (byte at index 8) — mask to unsigned
        val ttl = data[8].toInt() and 0xFF
        if (ttl <= 0) {
            Logger.d("BleManager", "Packet TTL expired: $packetId")
            return null
        }

        // Extract hopCount (byte at index 9)
        val hopCount = data[9].toInt()

        // Extract payload (skip header)
        val payload = data.sliceArray(10 until data.size)
        val content = payload.decodeToString()

        return try {
            MeshMessage(
                id = packetId,
                senderId = sourceAddress,
                receiverId = localDeviceAddress,
                content = content,
                type = MessageType.TEXT,
                timestamp = Clock.System.now(),
                status = MeshMessageStatus.DELIVERED,
                ttl = ttl,
                hopCount = hopCount
            )
        } catch (e: Exception) {
            Logger.e("BleManager", "Failed to parse received data", e)
            null
        }
    }

    /**
     * Cleans old entries from the deduplication cache.
     */
    private fun cleanDeduplicationCache() {
        val now = Clock.System.now().toEpochMilliseconds()
        seenPackets.entries.removeAll { (_, timestamp) ->
            now - timestamp > BleConstants.DEDUPLICATION_WINDOW_MS
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
        Logger.i("BleManager", "cleanup() called")
        stopScanning()
        stopMeshService()
        runBlocking {
            try {
                bleCentral.cleanup()
            } catch (e: Exception) {
                Logger.e("BleManager", "Error during bleCentral.cleanup()", e)
            }
        }
    }

    /**
     * Fully destroys the BleManager and cancels all internal coroutines.
     * Call this when the instance is no longer needed (e.g., on app exit).
     */
    fun destroy() {
        Logger.i("BleManager", "destroy() called - cancelling internal scope")
        cleanup()
        scope.cancel()
        bleCentral.destroy()
    }

    public suspend fun getPeripheral(address: String): Peripheral? {
        return discoveredDevices.value.find {it.identifier.toString() == address}
    }
}
