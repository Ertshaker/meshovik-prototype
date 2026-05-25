package com.meshovik.ble.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresPermission
import com.juul.kable.Advertisement
import com.meshovik.BleAdvertiser
import com.meshovik.BleChunker
import com.meshovik.BleDevice
import com.meshovik.BleGattServer
import com.meshovik.BleReassembler
import com.meshovik.BleScanner
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.time.Clock

/**
 * BLE Manager - orchestrates scanning, advertising, connections, and message routing.
 * Uses Kable-based BLE components.
 */
class BleManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bleScanner = BleScanner(context)
    private val bleAdvertiser = BleAdvertiser(context)
    private val bleChunker = BleChunker(mtu = 20)
    private val bleReassembler = BleReassembler()

    // Advertisements cache: address -> Advertisement
    private val advertisementsCache = mutableMapOf<String, Advertisement>()
    // Connected devices map: address -> BleDevice
    private val connectedDevices = mutableMapOf<String, BleDevice>()
    private val deviceObservationJobs = mutableMapOf<String, Job>()

    // State
    private val _discoveredDevices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshDevice>> = _discoveredDevices.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()
    private val bleGattServer = BleGattServer(context)
    private val connectingDevices = mutableSetOf<String>()

    private fun isConnecting(address: String): Boolean = connectingDevices.contains(address)
    // Local device info
    private var localDeviceAddress: String = ""
    private var localDeviceName: String = ""

    private var scanJob: Job? = null

    init {
        initializeLocalDeviceInfo()
        observeAdvertisingState()
        observeGattServerData()
    }

    /**
     * Initializes local device info.
     */
    private fun initializeLocalDeviceInfo() {
        localDeviceAddress = "Skibidi${(1..10).random()}"
        localDeviceName = "Meshovik Device"
        Timber.i("Local device: $localDeviceName ($localDeviceAddress)")
    }

    private fun observeGattServerData() {
        scope.launch {
            bleGattServer.receivedData.collect { rawData ->
                Timber.i("GATT Server received ${rawData.size} bytes")

                val messageBytes = bleReassembler.onChunk(rawData)
                if (messageBytes != null) {
                    val parsedMessage = parseReceivedData(messageBytes)
                    if (parsedMessage != null) {
                        _receivedMessages.update { current ->
                            current + parsedMessage
                        }
                        Timber.i("✅ Message added to receivedMessages: ${parsedMessage.content}")
                    }
                }
            }
        }
    }

    /**
     * Observes advertising state changes.
     */
    private fun observeAdvertisingState() {
        scope.launch {
            bleAdvertiser.advertisingState.collectLatest { isAdvertising ->
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
     * Starts the mesh service (advertising).
     */
    @SuppressLint("MissingPermission")
    fun startMeshService(): Flow<Boolean> {
        val resultFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        scope.launch {
            try {
                bleAdvertiser.startAdvertising()
                bleGattServer.start()
                resultFlow.emit(true)
                Timber.i("Mesh service started (advertising)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start mesh service")
                resultFlow.emit(false)
            }
        }
        return resultFlow
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startGattServer() {
        bleGattServer.start()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopGattServer() {
        bleGattServer.stop()
    }
    /**
     * Stops the mesh service.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopMeshService() {
        bleAdvertiser.stopAdvertising()
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
            Timber.w("Bluetooth may not be enabled, attempting to scan anyway")
            // Don't return - let the scan attempt and handle errors downstream
        }

        _isScanning.value = true
        scanJob = scope.launch {
            bleScanner.scan().collectLatest { advertisement ->
                val address = advertisement.identifier
                Timber.d("BLE advertisement: ${advertisement.name ?: "Unknown"} ($address)")
                
                // Cache advertisement
                advertisementsCache[address] = advertisement
                
                // Convert to MeshDevice
                val device = advertisement.toMeshDevice()
                
                // Update discovered devices
                _discoveredDevices.update { devices ->
                    val existingIndex = devices.indexOfFirst { it.address == address }
                    if (existingIndex >= 0) {
                        devices.toMutableList().apply {
                            this[existingIndex] = device
                        }
                    } else {
                        devices + device
                    }
                }

                // Auto-connect to discovered device
                if (!connectedDevices.containsKey(address) &&
                    address != localDeviceAddress &&           // ← не коннектимся к себе
                    !isConnecting(address)) {                  // ← добавь проверку

                    connectToDevice(advertisement)
                }
            }
        }
    }

    /**
     * Converts Advertisement to MeshDevice.
     */
    private fun Advertisement.toMeshDevice(): MeshDevice {
        return MeshDevice(
            id = this.identifier,
            name = this.name ?: "Unknown",
            address = this.identifier,
            rssi = this.rssi ?: 0,
            lastSeen = Clock.System.now(),
            isOnline = true,
            hopCount = 0
        )
    }

    /**
     * Stops scanning for devices.
     */
    fun stopScanning() {
        bleScanner.stopScanning()
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    /**
     * Connects to a device and starts observing its data.
     */
    suspend fun connectToDevice(advertisement: Advertisement): BleDevice? {
        val address = advertisement.identifier

        if (connectedDevices.containsKey(address) || connectingDevices.contains(address) || address == localDeviceAddress) {

            return connectedDevices[address]
        }

        connectingDevices.add(address)
        _connectionStates.update { it + (address to ConnectionState.Connecting(address)) }

        return try {
            val device = BleDevice(advertisement)
            Timber.i("Connecting to: $address")

            device.connect()
            connectedDevices[address] = device

            _connectionStates.update { it + (address to ConnectionState.Connected(address)) }

            // Start observing data from this device
            startObservingDevice(device, address)
            Timber.i("Successfully connected to: $address")

            device
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to device: $address")
            null
        } finally {
            connectingDevices.remove(address)
        }
    }

    suspend fun connectToDevice(address: String): BleDevice? {
        val advertisement = advertisementsCache[address]
            ?: run {
                Timber.w("Advertisement not found in cache for address: $address")
                return null
            }

        return connectToDevice(advertisement)  // вызов существующей функции
    }

    /**
     * Starts observing data from a connected device.
     */
    private fun startObservingDevice(device: BleDevice, address: String) {
        val job = scope.launch {
            try {
                device.observe().collectLatest { chunk ->
                    val message = bleReassembler.onChunk(chunk)
                    if (message != null) {
                        val parsedMessage = parseReceivedData(message)
                        if (parsedMessage != null) {
                            _receivedMessages.update { it + parsedMessage }
                            Timber.i("Message received: ${parsedMessage.id} from $address")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error observing device $address")
            }
        }
        deviceObservationJobs[address] = job
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
            ttl = 5,
            hopCount = 0
        )

        scope.launch {
            val packetData = createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = content.toByteArray(Charsets.UTF_8)
            )

            val success = sendData(targetAddress, packetData)
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
            ttl = 5,
            hopCount = 0
        )

        scope.launch {
            val packetData = createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = content.toByteArray(Charsets.UTF_8)
            )

            // Send to all connected devices
            connectedDevices.keys.forEach { address ->
                sendData(address, packetData)
            }
            Timber.i("Broadcast message sent: $messageId to ${connectedDevices.size} devices")
        }

        return message
    }

    /**
     * Sends data to a connected device.
     */
    private suspend fun sendData(targetAddress: String, data: ByteArray): Boolean {
        return try {
            val device = connectedDevices[targetAddress]
                ?: run {
                    Timber.w("Device not connected: $targetAddress")
                    return false
                }

            val chunks = bleChunker.chunk(data)

            Timber.d("Sending ${data.size} bytes to $targetAddress in ${chunks.size} chunks")

            chunks.forEachIndexed { index, chunk ->
                device.write(chunk)
                Timber.d("Chunk $index/${chunks.size} sent (${chunk.size} bytes)")
                // delay(10) // иногда помогает стабильности
            }

            Timber.i("✅ Message successfully sent to $targetAddress")
            true
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to send data to $targetAddress")   // ← Вот это главное
            false
        }
    }

    /**
     * Creates a mesh packet with header (packetId + TTL + hopCount + payload).
     */
    private fun createMeshPacket(
        packetId: String,
        ttl: Int,
        hopCount: Int,
        payload: ByteArray
    ): ByteArray {
        val packetIdBytes = packetId.toByteArray(Charsets.UTF_8).take(8).toByteArray()
        val paddedPacketId = packetIdBytes + ByteArray(8 - packetIdBytes.size)

        val sizeBytes = ByteBuffer.allocate(4).putInt(payload.size).array()

        return paddedPacketId +
                byteArrayOf(ttl.toByte(), hopCount.toByte()) +
                sizeBytes +
                payload
    }

    /**
     * Parses received byte data into a MeshMessage.
     */
    private fun parseReceivedData(data: ByteArray): MeshMessage? {
        return try {
            val content = data.toString(Charsets.UTF_8).trim()
            Timber.i("Parsing content: '$content'")

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
            Timber.e(e, "Failed to parse")
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun cleanup() {
        stopScanning()
        stopMeshService()
        deviceObservationJobs.values.forEach { it.cancel() }
        deviceObservationJobs.clear()
        connectedDevices.clear()
        advertisementsCache.clear()
    }

    /**
     * Connection state for a BLE device.
     */
    sealed class ConnectionState {
        data class Connected(val address: String) : ConnectionState()
        data class Disconnected(val address: String) : ConnectionState()
        data class Connecting(val address: String) : ConnectionState()
    }
}
