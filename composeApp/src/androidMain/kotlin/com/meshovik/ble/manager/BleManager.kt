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
import com.meshovik.domain.entity.ControlMessageType
import com.meshovik.domain.entity.MeshControlMessage
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    public val bleChunker = BleChunker()
    private val bleReassembler = BleReassembler()

    private val _controlMessages = MutableSharedFlow<MeshControlMessage>(extraBufferCapacity = 8)
    val controlMessages: SharedFlow<MeshControlMessage> = _controlMessages.asSharedFlow()

    // Advertisements cache: address -> Advertisement
    private val advertisementsCache = mutableMapOf<String, Advertisement>()
    // Connected devices map: address -> BleDevice
    private val connectedDevices = mutableMapOf<String, BleDevice>()
    private val deviceObservationJobs = mutableMapOf<String, Job>()
    // Mapping: BLE MAC address -> stable Mesh ID (populated when first message received)
    private val bleToMeshIdMap = mutableMapOf<String, String>()
    // Reverse mapping: Mesh ID -> BLE MAC address (for sending messages by Mesh ID)
    private val meshIdToBleMap = mutableMapOf<String, String>()

    private val _binaryDataReceived = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 32)
    val binaryDataReceived: SharedFlow<Pair<String, ByteArray>> = _binaryDataReceived.asSharedFlow()
    // State
    private val _discoveredDevices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshDevice>> = _discoveredDevices.asStateFlow()

    private val _fileChunksReceived = MutableSharedFlow<Pair<String, ByteArray>>(
        extraBufferCapacity = 64
    )
    val fileChunksReceived: SharedFlow<Pair<String, ByteArray>> = _fileChunksReceived.asSharedFlow()
    private val _receivedMessages = MutableSharedFlow<MeshMessage>(
        extraBufferCapacity = 32,
        replay = 0
    )
    val receivedMessages: SharedFlow<MeshMessage> = _receivedMessages.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isMeshServiceActive = MutableStateFlow(false)
    val isMeshService: StateFlow<Boolean> = _isMeshServiceActive.asStateFlow()
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()
    private val bleGattServer = BleGattServer(context)
    private val connectingDevices = mutableSetOf<String>()

    private fun isConnecting(address: String): Boolean = connectingDevices.contains(address)
    private var localDeviceAddress: String = ""
    private var localDeviceName: String = ""

    private var scanJob: Job? = null

    init {
        initializeLocalDeviceInfo()
        observeAdvertisingState()
        observeGattServerData()
    }
    @SuppressLint("MissingPermission")
    private fun initializeLocalDeviceInfo() {
        localDeviceAddress = deviceIdProvider.getDeviceId()
        localDeviceName = deviceIdProvider.getUserName()
        // Broadcast our Mesh ID via Bluetooth device name so other devices learn it during scan
        bleAdvertiser.setMeshId(localDeviceAddress)
        Timber.i("Local device: $localDeviceName ($localDeviceAddress)")
    }

    private fun observeGattServerData() {
        scope.launch {
            bleGattServer.receivedData.collect { rawChunk ->
                Timber.i("GATT Server received ${rawChunk.size} bytes")

                val fullMessage = bleReassembler.onChunk("GATT_SERVER", rawChunk) ?: return@collect

                handleIncomingRawData("GATT_SERVER", fullMessage)
            }
        }
    }

    private fun handleIncomingRawData(source: String, data: ByteArray) {
        if (data.isEmpty()) return

        val firstByte = data[0].toInt() and 0xFF
        Timber.i("handleIncomingRawData from $source: ${data.size} bytes | first=0x${firstByte.toString(16)}")

        // IMAGE CHUNK — ослабляем условие
        if (firstByte == 0xF1) {
            try {
                val transferId = extractTransferId(data)
                val chunkData = data.copyOfRange(13, data.size)

                Timber.i("✅ IMAGE CHUNK | transferId=$transferId | ${chunkData.size} bytes")

                scope.launch {
                    _fileChunksReceived.emit(Pair(transferId, chunkData))
                }
                return
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse image chunk")
            }
        }

        // Control + MeshMessage...
        val controlMsg = tryParseControlMessage(data)
        if (controlMsg != null) {
            scope.launch {
                handleControlMessage(controlMsg, source)
                _controlMessages.emit(controlMsg)
            }
            return
        }

        if (data.size > 10 && data[0] == '{'.code.toByte()) {
            val parsed = parseReceivedData(data)
            if (parsed != null) {
                handleReceivedMessage(source, parsed)
                return
            }
        }

        Timber.w("Unknown data from $source: ${data.size} bytes, first=0x${firstByte.toString(16)}")
    }
    private fun handleControlMessage(controlMsg: MeshControlMessage, sourceBleAddress: String) {
        when (controlMsg.type) {
            ControlMessageType.USER_INFO -> {
                if (!controlMsg.userName.isNullOrBlank()) {
                    updateDeviceUserName(sourceBleAddress, controlMsg.senderId, controlMsg.userName!!)
                }
            }
            ControlMessageType.REQUEST_USER_INFO -> {
                // Кто-то попросил наше имя — сразу отправляем
                sendUserInfo(controlMsg.senderId)
            }
            ControlMessageType.READY_FOR_TRANSFER -> {
                scope.launch { _controlMessages.emit(controlMsg) }
            }
        }
    }
    private fun updateDeviceUserName(bleAddress: String, meshId: String, userName: String) {
        _discoveredDevices.update { devices ->
            devices.map { device ->
                if (device.address == bleAddress || device.meshId == meshId) {
                    device.copy(userName = userName)
                } else device
            }
        }
        Timber.i("Updated userName: $bleAddress / $meshId → $userName")
    }
    private fun extractTransferId(data: ByteArray): String {
        if (data.size < 17) {
            Timber.e("Я ПОЛНЫЙ ДОЛБАЁБ")
            return ""
        }
        val idBytes = data.copyOfRange(1, 13)
        return idBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun tryParseControlMessage(data: ByteArray): MeshControlMessage? {
        return try {
            val json = data.decodeToString()
            Json.decodeFromString<MeshControlMessage>(json)
        } catch (e: Exception) {
            null  // не control-сообщение — нормально
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
    fun sendUserInfo(targetAddress: String) {
        val controlMsg = MeshControlMessage(
            type = ControlMessageType.USER_INFO,
            senderId = localDeviceAddress,
            receiverId = targetAddress,
            userName = localDeviceName,
            attachmentId = null
        )

        scope.launch {
            try {
                val json = Json.encodeToString(controlMsg)
                val packetData = createMeshPacket(
                    packetId = UUID.randomUUID().toString().take(8),
                    ttl = 3,
                    hopCount = 0,
                    payload = json.encodeToByteArray()
                )

                val targetBle = meshIdToBleMap[targetAddress] ?: targetAddress
                sendData(targetBle, packetData)
                Timber.i("Sent USER_INFO to $targetAddress: $localDeviceName")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send USER_INFO")
            }
        }
    }
    fun requestUserInfo(targetAddress: String) {
        val controlMsg = MeshControlMessage(
            type = ControlMessageType.REQUEST_USER_INFO,
            senderId = localDeviceAddress,
            receiverId = targetAddress,
            attachmentId = null
        )

        scope.launch {
            try {
                val json = Json.encodeToString(controlMsg)
                val packetData = createMeshPacket(
                    packetId = UUID.randomUUID().toString().take(8),
                    ttl = 3,
                    hopCount = 0,
                    payload = json.encodeToByteArray()
                )
                val targetBle = meshIdToBleMap[targetAddress] ?: targetAddress
                sendData(targetBle, packetData)
                Timber.i("→ Requested USER_INFO from $targetAddress")
            } catch (e: Exception) {
                Timber.e(e, "Failed to request USER_INFO")
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
                _isAdvertising.value = true
                _isScanning.value = true
                _isMeshServiceActive.value = true
                resultFlow.emit(true)
                Timber.i("Mesh service started (advertising)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start mesh service")
                resultFlow.emit(false)
            }
        }
        return resultFlow
    }
    /**
     * Stops the mesh service.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopMeshService(): Flow<Boolean> {
        val resultFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        scope.launch {
            try {
                bleAdvertiser.stopAdvertising()
                bleScanner.stopScanning()
                bleGattServer.stop()
                _isAdvertising.value = false
                _isScanning.value = false
                _isMeshServiceActive.value = false
                resultFlow.emit(true)
                Timber.i("Mesh service started (advertising)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start mesh service")
                resultFlow.emit(false)
            }
        }
        return resultFlow
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

                advertisementsCache[address] = advertisement

                _discoveredDevices.update { currentDevices ->
                    val newDevice = advertisement.toMeshDevice()

                    val existingIndex = currentDevices.indexOfFirst {
                        it.address == newDevice.address ||
                                (it.meshId.isNotEmpty() && it.meshId == newDevice.meshId)
                    }

                    if (existingIndex >= 0) {
                        val existing = currentDevices[existingIndex]
                        currentDevices.toMutableList().apply {
                            this[existingIndex] = newDevice.copy(
                                meshId = existing.meshId.ifBlank { newDevice.meshId },
                                userName = existing.userName.ifBlank { newDevice.userName }
                            )
                        }
                    } else {
                        currentDevices + newDevice
                    }
                }

                // Auto-connect
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
            meshId = discoveredMeshId,
            wifiDirectAddress = null,
            userName = ""
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

            startObservingMtu(device, address)
            startObservingDevice(device, address)   // теперь безопасно
            Timber.i("✅ Successfully connected and observing: $address")

            sendUserInfo(address)
            requestUserInfo(address)

            device
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to device: $address")
            null
        } finally {
            connectingDevices.remove(address)
        }
    }

    private fun startObservingMtu(device: BleDevice, address: String) {
        scope.launch {
            device.mtu.collect { mtu ->
                mtu?.let {
                    bleChunker.updateMtu(it)
                }
            }
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
                    val message = bleReassembler.onChunk(address, chunk)
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

        val device = _discoveredDevices.value.find { it.address == sourceBleAddress }
        if (device?.userName.isNullOrBlank()) {
            scope.launch { requestUserInfo(message.senderId) }
        }

        // Специальная обработка WIFI_HANDSHAKE (оставляем как есть)
        if (message.content.contains("WIFI_HANDSHAKE")) {
            try {
                val data = Json.decodeFromString<Map<String, String>>(message.content)
                if (data["type"] == "WIFI_HANDSHAKE") {
                    val wifiMac = data["wifiDirectAddress"]
                    val senderMeshId = data["meshId"]

                    if (!wifiMac.isNullOrBlank()) {
                        Timber.i("✅ Получен Wi-Fi Direct адрес от $sourceBleAddress → $wifiMac")
                        _discoveredDevices.update { devices ->
                            devices.map { device ->
                                if (device.address == sourceBleAddress) {
                                    device.copy(wifiDirectAddress = wifiMac)
                                } else device
                            }
                        }
                    }
                    if (!senderMeshId.isNullOrBlank()) {
                        bleToMeshIdMap[sourceBleAddress] = senderMeshId
                        meshIdToBleMap[senderMeshId] = sourceBleAddress
                    }
                    return
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse WIFI_HANDSHAKE")
            }
        }

        scope.launch {
            _receivedMessages.emit(finalMessage)
        }

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

        scope.launch {
        _receivedMessages.emit(message)
        }

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

            Timber.d("Sending ${data.size} bytes to $targetAddress in ${chunks.size} chunks (chunkSize=${bleChunker.chunkSize})")

            chunks.forEachIndexed { index, chunk ->
                device.write(chunk)
                // Небольшая задержка помогает стабильности при больших передачах
                if (index % 8 == 0 && chunks.size > 10) {
                    delay(5)
                }
            }

            Timber.i("✅ Data successfully sent to $targetAddress (${data.size} bytes)")
            true
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to send data to $targetAddress")
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendBinaryData(targetAddress: String, data: ByteArray): Boolean {
        val targetBle = meshIdToBleMap[targetAddress] ?: targetAddress
        return sendData(targetBle, data)   // уже существующий приватный метод
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
    suspend fun sendFilePacket(targetAddress: String, fullPacket: ByteArray): Boolean {
        return try {
            val device = connectedDevices[targetAddress] ?: return false

            // Важно: НЕ делаем chunking повторно!
            device.write(fullPacket)

            // Небольшая задержка для больших пакетов
            if (fullPacket.size > 300) {
                delay(8)
            }

            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to send file packet")
            false
        }
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
        scope.launch {
            _receivedMessages.emit(message)
        }
        // Сохраняем сразу в локальный список


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

    fun sendReadyForTransfer(targetAddress: String, attachmentId: String) {
        val controlMsg = MeshControlMessage(
            type = ControlMessageType.READY_FOR_TRANSFER,
            attachmentId = attachmentId,
            senderId = localDeviceAddress,
            receiverId = targetAddress
        )

        // Локально эммитим
        scope.launch {
            _controlMessages.emit(controlMsg)
        }

        // === КРИТИЧНОЕ ИСПРАВЛЕНИЕ ===
        scope.launch {
            // Правильно определяем реальный BLE адрес получателя
            val targetBleAddress = when {
                targetAddress.startsWith("Mesh") -> meshIdToBleMap[targetAddress]
                else -> targetAddress
            } ?: run {
                Timber.e("Cannot find BLE address for target: $targetAddress")
                return@launch
            }

            Timber.i("Sending READY_FOR_TRANSFER to MeshID=$targetAddress → BLE=$targetBleAddress")

            val json = Json.encodeToString(controlMsg)
            val payload = json.encodeToByteArray()

            val packetData = createMeshPacket(
                packetId = UUID.randomUUID().toString().take(8),
                ttl = 3,
                hopCount = 0,
                payload = payload
            )

            val sent = sendData(targetBleAddress, packetData)

            if (sent) {
                Timber.i("✅ READY_FOR_TRANSFER sent successfully to $targetBleAddress")
            } else {
                Timber.e("❌ Failed to send READY_FOR_TRANSFER to $targetBleAddress")
            }
        }
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

    fun setNewUserName(newUserName: String) {
        try {
            deviceIdProvider.setUserName(newUserName)
            localDeviceName = newUserName
            // Рассылаем новое имя всем, с кем мы когда-либо общались / кого видим
            val targets = mutableSetOf<String>()

            // 1. Все подключённые устройства
            targets.addAll(connectedDevices.keys)

            // 2. Все устройства из discoveredDevices (на всякий случай)
            _discoveredDevices.value.forEach { device ->
                if (device.address != localDeviceAddress) {
                    targets.add(device.address)
                }
            }

            // 3. Через meshId mapping (если есть)
            meshIdToBleMap.values.forEach { bleAddress ->
                if (bleAddress != localDeviceAddress) targets.add(bleAddress)
            }

            Timber.i("Broadcasting new username to ${targets.size} devices: $newUserName")

            // Отправляем USER_INFO каждому
            targets.forEach { target ->
                sendUserInfo(target)
            }
            Timber.i("Чё-то имя сохранилось")
        } catch(e: Exception) {
            Timber.e("Чё-то имя НЕ сохранилось")
        }
    }

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
        data class Connecting(val address: String) : ConnectionState()
    }
}
