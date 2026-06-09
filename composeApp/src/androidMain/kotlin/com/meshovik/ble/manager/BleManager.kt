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
import com.meshovik.core.util.DeviceIdProvider
import com.meshovik.domain.entity.Attachment
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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

    private val deviceIdProvider = DeviceIdProvider(context)

    private val bleScanner = BleScanner(context)
    private val bleAdvertiser = BleAdvertiser(context)
    private val bleChunker = BleChunker(mtu = 20)
    private val bleReassembler = BleReassembler()

    // Advertisements cache: address -> Advertisement
    private val advertisementsCache = mutableMapOf<String, Advertisement>()
    // Connected devices map: address -> BleDevice
    private val connectedDevices = mutableMapOf<String, BleDevice>()
    private val deviceObservationJobs = mutableMapOf<String, Job>()
    // Mapping: BLE MAC address -> stable Mesh ID (populated when first message received)
    private val bleToMeshIdMap = mutableMapOf<String, String>()
    // Reverse mapping: Mesh ID -> BLE MAC address (for sending messages by Mesh ID)
    private val meshIdToBleMap = mutableMapOf<String, String>()

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
        localDeviceAddress = deviceIdProvider.getDeviceId()
        localDeviceName = deviceIdProvider.getUserName()
        // Broadcast our Mesh ID via Bluetooth device name so other devices learn it during scan
        bleAdvertiser.setMeshId(localDeviceAddress)
        Timber.i("Local device: $localDeviceName ($localDeviceAddress)")
    }

    private fun observeGattServerData() {
        scope.launch {
            bleGattServer.receivedData.collect { rawData ->
                Timber.i("GATT Server received ${rawData.size} bytes")

                val messageBytes = bleReassembler.onChunk("GATT_SERVER", rawData)
                if (messageBytes != null) {
                    val parsedMessage = parseReceivedData(messageBytes)
                    if (parsedMessage != null) {
                        handleReceivedMessage("GATT_SERVER", parsedMessage)   // или source address если есть
                        Timber.i("✅ Message added to receivedMessages: ${parsedMessage.content}")
                    }
                }
            }
        }
    }

    /**
     * Returns the stable Mesh ID for a given BLE MAC address, or null if not yet known.
     */
    fun getMeshIdByBleAddress(bleAddress: String): String? = bleToMeshIdMap[bleAddress]

    /**
     * Returns all known BLE address → Mesh ID mappings.
     */
    fun getBleToMeshIdMap(): Map<String, String> = bleToMeshIdMap.toMap()

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
            // Use collect (not collectLatest) so connectToDevice is not cancelled on each new advertisement
            bleScanner.scan().collect { advertisement ->
                val address = advertisement.identifier
                Timber.d("BLE advertisement: ${advertisement.name ?: "Unknown"} ($address)")
                
                // Cache advertisement
                advertisementsCache[address] = advertisement
                
                // Update discovered devices, preserving existing meshId
                _discoveredDevices.update { devices ->
                    val existingIndex = devices.indexOfFirst { it.address == address }
                    if (existingIndex >= 0) {
                        // Update but keep the known meshId
                        val existing = devices[existingIndex]
                        devices.toMutableList().apply {
                            this[existingIndex] = advertisement.toMeshDevice().copy(meshId = existing.meshId)
                        }
                    } else {
                        devices + advertisement.toMeshDevice()
                    }
                }

                // Auto-connect to discovered device (launch separately so scan is not blocked)
                if (!connectedDevices.containsKey(address) &&
                    address != localDeviceAddress &&
                    !isConnecting(address)) {

                    launch { connectToDevice(advertisement) }
                }
            }
        }
    }

    /**
     * Converts Advertisement to MeshDevice.
     */
    private fun Advertisement.toMeshDevice(): MeshDevice {
        // If the device name looks like a Mesh ID (starts with "Mesh"), use it as meshId
        val advName = this.name ?: ""
        val discoveredMeshId = if (advName.startsWith("Mesh") && advName.length == 12) advName else ""

        // Also populate reverse mapping immediately if we learn the meshId from advertisement
        if (discoveredMeshId.isNotEmpty()) {
            val bleAddr = this.identifier
            if (!bleToMeshIdMap.containsKey(bleAddr)) {
                bleToMeshIdMap[bleAddr] = discoveredMeshId
                meshIdToBleMap[discoveredMeshId] = bleAddr
                Timber.i("Learned Mesh ID from advertisement: $bleAddr → $discoveredMeshId")
            }
        }

        return MeshDevice(
            id = this.identifier,
            name = advName.ifEmpty { "Unknown" },
            address = this.identifier,
            rssi = this.rssi ?: 0,
            lastSeen = Clock.System.now(),
            isOnline = true,
            hopCount = 0,
            meshId = discoveredMeshId
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

        if (connectedDevices.containsKey(address) || address == localDeviceAddress) {
            return connectedDevices[address]
        }

        if (connectingDevices.contains(address)) {
            return null
        }

        connectingDevices.add(address)
        _connectionStates.update { it + (address to ConnectionState.Connecting(address)) }

        return try {
            val device = BleDevice(advertisement)
            Timber.i("Connecting to: $address")

            device.connect()
            connectedDevices[address] = device

            _connectionStates.update { it + (address to ConnectionState.Connected(address)) }

            startObservingDevice(device, address)   // теперь безопасно
            Timber.i("✅ Successfully connected and observing: $address")

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
        if (deviceObservationJobs.containsKey(address)) {
            Timber.d("Already observing device $address")
            return
        }

        val job = scope.launch {
            try {
                device.observe().collectLatest { chunk ->
                    val message = bleReassembler.onChunk("GATT_SERVER", chunk)
                    if (message != null) {
                        val parsedMessage = parseReceivedData(message)
                        if (parsedMessage != null) {
                            // Learn the sender's Mesh ID from the message
                            val senderMeshId = parsedMessage.senderId
                            if (senderMeshId.isNotEmpty() && !bleToMeshIdMap.containsKey(address)) {
                                bleToMeshIdMap[address] = senderMeshId
                                meshIdToBleMap[senderMeshId] = address
                                Timber.i("Learned Mesh ID: $address → $senderMeshId")
                                // Update the MeshDevice with the discovered meshId
                                _discoveredDevices.update { devices ->
                                    devices.map { d ->
                                        if (d.address == address) d.copy(meshId = senderMeshId) else d
                                    }
                                }
                            }
                            handleReceivedMessage(address, parsedMessage)
                            Timber.i("Message received: ${parsedMessage.id} from $address (meshId=$senderMeshId)")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error observing device $address")
            }
        }
        deviceObservationJobs[address] = job
    }

    private fun handleReceivedMessage(sourceBleAddress: String, message: MeshMessage) {
        var finalMessage = message

        // Если senderId выглядит как MeshID — сохраняем маппинг
        if (message.senderId.startsWith("Mesh") || message.senderId.length > 20) {
            bleToMeshIdMap[sourceBleAddress] = message.senderId
            meshIdToBleMap[message.senderId] = sourceBleAddress
            Timber.i("Mapped: $sourceBleAddress <-> ${message.senderId}")
        } else {
            // Если пришёл BLE address — пробуем найти MeshID
            val meshId = bleToMeshIdMap[sourceBleAddress]
            if (meshId != null) {
                finalMessage = message.copy(senderId = meshId)
            }
        }

        _receivedMessages.update { it + finalMessage }
        Timber.i("✅ Saved message: ${finalMessage.senderId} -> ${finalMessage.receiverId} | ${finalMessage.content}")
    }

    /**
     * Sends a text message to a specific device.
     */
    fun sendMessage(targetAddress: String, content: String): MeshMessage {
        val messageId = UUID.randomUUID().toString().take(8)
        val message = MeshMessage(
            id = messageId,
            senderId = localDeviceAddress,           // твой стабильный MeshID
            receiverId = targetAddress,              // MeshID или BLE address — не важно, обработается
            content = content,
            type = MessageType.TEXT,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            status = MeshMessageStatus.SENT,
            ttl = 5,
            hopCount = 0
        )

        // Сохраняем сразу
        _receivedMessages.update { it + message }

        scope.launch {
            val packetData = createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = Json.encodeToString(message).encodeToByteArray()
            )

            val targetBle = meshIdToBleMap[targetAddress] ?: targetAddress
            sendData(targetBle, packetData)
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
            timestamp = Clock.System.now().toEpochMilliseconds(),
            status = MeshMessageStatus.PENDING,
            ttl = 5,
            hopCount = 0
        )

        scope.launch {
            val packetData = createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = Json.encodeToString(message).encodeToByteArray()
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
     * Отправляет сообщение с вложением:
     * - По BLE передаются только метаданные (Attachment)
     * - Сам файл передаётся отдельно через Wi-Fi Direct (FileTransferManager)
     *
     * @param targetAddress  MeshID или BLE-адрес получателя
     * @param attachment     Метаданные вложения
     * @param caption        Подпись к вложению (опционально)
     * @return Созданное MeshMessage с вложением
     */
    fun sendMessageWithAttachment(
        targetAddress: String,
        attachment: Attachment,
        caption: String = ""
    ): MeshMessage {
        val messageId = UUID.randomUUID().toString().take(8)
        val message = MeshMessage(
            id = messageId,
            senderId = localDeviceAddress,
            receiverId = targetAddress,
            content = caption,
            type = MessageType.ATTACHMENT,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            status = MeshMessageStatus.SENT,
            ttl = 5,
            hopCount = 0,
            attachment = attachment
        )

        // Сохраняем сразу в локальный список
        _receivedMessages.update { it + message }

        scope.launch {
            val packetData = createMeshPacket(
                packetId = messageId,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = Json.encodeToString(message).encodeToByteArray()
            )

            val targetBle = meshIdToBleMap[targetAddress] ?: targetAddress
            val sent = sendData(targetBle, packetData)
            if (sent) {
                Timber.i("✅ Attachment metadata sent via BLE: ${attachment.id} (${attachment.fileName})")
            } else {
                Timber.e("❌ Failed to send attachment metadata via BLE: ${attachment.id}")
            }
        }

        return message
    }

    /**
     * Parses received byte data into a MeshMessage.
     */
    private fun parseReceivedData(data: ByteArray): MeshMessage? {
        return try {
            val json = data.decodeToString()
            Json.decodeFromString<MeshMessage>(json)
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
