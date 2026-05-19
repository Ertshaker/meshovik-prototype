package com.meshovik.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Looper
import android.os.ParcelUuid
import com.meshovik.ble.model.BleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * BLE Mesh Service - the core GATT service for the mesh network.
 * All BLE operations run on Dispatchers.IO to prevent ANR.
 * Implements flooding-based mesh with TTL and deduplication.
 */
class BleMeshService(
    private val context: Context
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sendConfirmations = MutableSharedFlow<SendConfirmation>(extraBufferCapacity = 64)
    val sendConfirmations: SharedFlow<SendConfirmation> = _sendConfirmations.asSharedFlow()

    data class SendConfirmation(
        val messageId: String,
        val success: Boolean,
        val deviceAddress: String
    )

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var advertiseCallback: AdvertiseCallback? = null

    private val _advertisingState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val advertisingState: SharedFlow<Boolean> = _advertisingState.asSharedFlow()

    // StateFlow for external observers to track advertising state
    private val _advertisingStateAsState = MutableStateFlow(false)
    val advertisingStateAsStateFlow: StateFlow<Boolean> = _advertisingStateAsState.asStateFlow()

    private val _receivedData = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ReceivedPacket> = _receivedData.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    private val seenPackets = mutableMapOf<String, Long>()
    private val connectedDevices = mutableSetOf<String>()
    private val gattCache = ConcurrentHashMap<String, GattConnection>()
    private val connectionSemaphore = Semaphore(BleConstants.MAX_CONCURRENT_CONNECTIONS)

    // ✅ FIX #1: Мапа для колбэков уведомлений (асинхронная обработка writeDescriptor)
    private val pendingNotificationCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    // FIX #5: Connection pool with keep-alive and auto-reconnect
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private var keepAliveJob: kotlinx.coroutines.Job? = null

    // FIX #1: Per-device connection mutex to prevent concurrent connection attempts
    private val connectionMutexes = ConcurrentHashMap<String, Mutex>()

    // FIX #4: Track connection attempts in progress to prevent duplicates
    private val connectionInProgress = ConcurrentHashMap<String, AtomicBoolean>()

    data class GattConnection(
        val gatt: BluetoothGatt,
        val dataCharacteristic: BluetoothGattCharacteristic? = null,
        val cccDescriptor: BluetoothGattDescriptor? = null,
        var isConnected: Boolean = true,
        var notificationsEnabled: Boolean = false,
        // ✅ FIX #2: Флаги для защиты от дублирования колбэков
        var isMtuRequested: Boolean = false,
        var isServicesDiscovered: Boolean = false,
        // FIX #4: Track if service discovery is in progress
        var isDiscoveryInProgress: Boolean = false,
        // FIX #5: Track last activity time for health monitoring
        var lastActivityTime: Long = System.currentTimeMillis()
    ) {
        fun touch() {
            lastActivityTime = System.currentTimeMillis()
        }
        
        fun isStale(timeoutMs: Long = BleConstants.CONNECTION_KEEP_ALIVE_INTERVAL_MS * 2): Boolean {
            return System.currentTimeMillis() - lastActivityTime > timeoutMs
        }
    }

    data class ReceivedPacket(
        val packetId: String,
        val ttl: Int,
        val hopCount: Int,
        val payload: ByteArray,
        val sourceAddress: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReceivedPacket) return false
            if (packetId != other.packetId) return false
            if (ttl != other.ttl) return false
            if (hopCount != other.hopCount) return false
            if (sourceAddress != other.sourceAddress) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = packetId.hashCode()
            result = 31 * result + ttl
            result = 31 * result + hopCount
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + sourceAddress.hashCode()
            return result
        }
    }

    sealed class ConnectionState {
        data class Connected(val address: String) : ConnectionState()
        data class Disconnected(val address: String) : ConnectionState()
        data class Connecting(val address: String) : ConnectionState()
    }

    private fun assertNotMainThread(method: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Timber.e("CRITICAL: BLE operation '$method' called from main thread!")
            throw IllegalStateException("BLE operation '$method' must not be called from main thread!")
        }
    }

    @SuppressLint("MissingPermission")
    fun startService(): Flow<Boolean> = callbackFlow {
        withContext(Dispatchers.IO) {
            if (bluetoothAdapter == null) {
                Timber.e("Bluetooth adapter is null")
                trySend(false)
                close()
                return@withContext
            }
            try {
                gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
                gattServer?.addService(createMeshService())
                Timber.i("GATT server started with mesh service")
                startAdvertising()
                // FIX #5: Start keep-alive job for connection pool health monitoring
                startKeepAliveJob()
                trySend(true)
            } catch (e: SecurityException) {
                Timber.e(e, "Missing BLE permissions for starting service")
                trySend(false)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start BLE mesh service")
                trySend(false)
            }
        }
        awaitClose {
            serviceScope.launch { stopService() }
        }
    }

    // FIX #5: Keep-alive job that monitors and maintains connection health
    private fun startKeepAliveJob() {
        keepAliveJob?.cancel()
        keepAliveJob = serviceScope.launch {
            while (true) {
                delay(BleConstants.CONNECTION_KEEP_ALIVE_INTERVAL_MS)
                checkConnectionHealth()
            }
        }
    }

    /**
     * FIX #5: Check all cached connections for staleness and attempt reconnection.
     * Also cleans up dead connections to free resources.
     */
    @SuppressLint("MissingPermission")
    private suspend fun checkConnectionHealth() {
        val staleAddresses = mutableListOf<String>()
        
        for ((address, connection) in gattCache) {
            if (connection.isStale()) {
                Timber.w("Stale connection detected: $address (last activity: ${System.currentTimeMillis() - connection.lastActivityTime}ms ago)")
                staleAddresses.add(address)
            }
        }
        
        // FIX #5: Instead of immediately removing stale connections, try to refresh them first
        for (address in staleAddresses) {
            val connection = gattCache[address]
            if (connection != null && connectedDevices.contains(address)) {
                // Connection is in connectedDevices but stale - try to refresh by re-discovering services
                Timber.d("Attempting to refresh stale connection: $address")
                try {
                    connection.gatt.discoverServices()
                    connection.touch() // Update activity time
                } catch (e: Exception) {
                    Timber.w(e, "Failed to refresh stale connection: $address")
                    // Only close if refresh fails
                    gattCache.remove(address)
                    connectedDevices.remove(address)
                    try { 
                        connection.gatt.disconnect()
                        connection.gatt.close() 
                    } catch (e: Exception) { 
                        Timber.w(e, "Error closing stale connection") 
                    }
                    connectionSemaphore.release()
                    _connectionState.emit(ConnectionState.Disconnected(address))
                }
            } else {
                // Connection is not in connectedDevices - safe to remove
                Timber.d("Removing dead connection: $address")
                gattCache[address]?.let { conn ->
                    try { 
                        conn.gatt.disconnect()
                        conn.gatt.close() 
                    } catch (e: Exception) { 
                        Timber.w(e, "Error closing stale connection") 
                    }
                }
                gattCache.remove(address)
                connectionSemaphore.release()
                _connectionState.emit(ConnectionState.Disconnected(address))
            }
        }
        
        // Note: Reconnection is handled by BleManager when devices are discovered
    }

    private fun createMeshService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleConstants.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val dataCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MESH_DATA_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val cccDescriptor = BluetoothGattDescriptor(
            BleConstants.CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        dataCharacteristic.addDescriptor(cccDescriptor)

        val controlCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MESH_CONTROL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(dataCharacteristic)
        service.addCharacteristic(controlCharacteristic)
        return service
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            serviceScope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.i("Device connected to GATT server: ${device.address}")
                        connectedDevices.add(device.address)
                        _connectionState.emit(ConnectionState.Connected(device.address))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.i("Device disconnected from GATT server: ${device.address}")
                        connectedDevices.remove(device.address)
                        gattCache[device.address]?.let { conn ->
                            try { conn.gatt.disconnect(); conn.gatt.close() } catch (e: Exception) { Timber.w(e, "Error closing GATT") }
                        }
                        gattCache.remove(device.address)
                        connectionSemaphore.release()
                        _connectionState.emit(ConnectionState.Disconnected(device.address))
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.MESH_DATA_CHARACTERISTIC_UUID) {
                val dataCopy = value.copyOf()
                val address = device.address
                Timber.d("Received data from $address: ${dataCopy.size} bytes")
                serviceScope.launch { processReceivedPacket(dataCopy, address) }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) { Timber.e(e, "Missing permission for sendResponse") }
                }
            }
        }
    }

    private suspend fun processReceivedPacket(data: ByteArray, sourceAddress: String) {
        if (data.size < 10) { Timber.w("Packet too small: ${data.size}"); return }

        val packetIdBytes = data.sliceArray(0 until 8)
        val packetId = packetIdBytes.joinToString("") { "%02x".format(it) }
        val ttl = data[8].toInt() and 0xFF
        val hopCount = data[9].toInt() and 0xFF

        val now = System.currentTimeMillis()
        if (seenPackets.containsKey(packetId)) { Timber.d("Duplicate ignored: $packetId"); return }
        seenPackets[packetId] = now
        cleanDeduplicationCache()

        if (ttl <= 0) { Timber.d("TTL expired: $packetId"); return }
        if (hopCount >= BleConstants.MAX_HOP_COUNT) { Timber.d("Max hops exceeded: $packetId"); return }

        val payload = data.sliceArray(10 until data.size)
        _receivedData.emit(ReceivedPacket(packetId, ttl, hopCount, payload, sourceAddress))
        Timber.i("Packet received: $packetId, TTL=$ttl, hops=$hopCount, payload=${payload.size}B, from=$sourceAddress")

        if (ttl > 1) {
            val forwardedPacket = packetIdBytes + byteArrayOf((ttl - 1).toByte(), (hopCount + 1).toByte()) + payload
            Timber.d("Flooding $packetId (TTL: $ttl -> ${ttl - 1})")
            broadcastToAllNeighbors(forwardedPacket, excludeAddress = sourceAddress)
        }
    }

    private fun cleanDeduplicationCache() {
        val now = System.currentTimeMillis()
        seenPackets.entries.removeAll { (_, ts) -> now - ts > BleConstants.DEDUPLICATION_WINDOW_MS }
    }

    @SuppressLint("MissingPermission")
    suspend fun broadcastToAllNeighbors(packet: ByteArray, excludeAddress: String? = null) {
        assertNotMainThread("broadcastToAllNeighbors")
        withContext(Dispatchers.IO) {
            if (connectedDevices.isEmpty()) return@withContext
            connectedDevices.filter { it != excludeAddress }.forEach { sendData(it, packet) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendData(deviceAddress: String, data: ByteArray): Boolean {
        assertNotMainThread("sendData")

        return withContext(Dispatchers.IO) {
            Timber.d(">>> sendData to $deviceAddress: ${data.size} bytes")

            // FIX #1: Get or create per-device mutex
            val mutex = connectionMutexes.computeIfAbsent(deviceAddress) { Mutex() }

            // FIX #1: Use mutex to prevent concurrent connection attempts
            mutex.withLock {
                val cachedConnection = gattCache[deviceAddress]
                Timber.d("Cached connection for $deviceAddress: ${if (cachedConnection != null) "FOUND (connected=${cachedConnection.isConnected})" else "NOT FOUND"}")
                Timber.d("Connected devices set: $connectedDevices")

                if (cachedConnection != null && cachedConnection.isConnected && cachedConnection.dataCharacteristic != null) {
                    Timber.d("Using cached GATT connection for $deviceAddress")
                    val result = writeViaCachedGatt(deviceAddress, cachedConnection, data)
                    Timber.d("Cached write result for $deviceAddress: $result")
                    return@withContext result
                }

                if (cachedConnection != null) {
                    Timber.w("Removing stale cached connection for $deviceAddress")
                    gattCache.remove(deviceAddress)
                    try { cachedConnection.gatt.close() } catch (e: Exception) { Timber.w(e, "Error closing stale connection") }
                }

                Timber.d("Falling back to new connection for $deviceAddress")
                val result = writeViaNewConnection(deviceAddress, data)
                Timber.d("New connection write result for $deviceAddress: $result")
                result
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeViaCachedGatt(deviceAddress: String, connection: GattConnection, data: ByteArray): Boolean {
        return try {
            val characteristic = connection.dataCharacteristic ?: run {
                Timber.w("Characteristic not found for $deviceAddress")
                gattCache.remove(deviceAddress)
                try { connection.gatt.disconnect(); connection.gatt.close() } catch (e: Exception) {}
                return false
            }
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            // FIX #5: Update last activity time before write
            connection.touch()
            val success = connection.gatt.writeCharacteristic(characteristic)
            if (!success) {
                Timber.w("Cached write returned false for $deviceAddress")
                gattCache.remove(deviceAddress)
                try { connection.gatt.disconnect(); connection.gatt.close() } catch (e: Exception) {}
            }
            success
        } catch (e: Exception) {
            Timber.e(e, "Cached write failed for $deviceAddress")
            gattCache.remove(deviceAddress)
            try { connection.gatt.disconnect(); connection.gatt.close() } catch (e: Exception) {}
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeViaNewConnection(deviceAddress: String, data: ByteArray): Boolean {
        val device = try { bluetoothAdapter?.getRemoteDevice(deviceAddress) }
        catch (e: IllegalArgumentException) { Timber.e("Invalid address: $deviceAddress"); return false } ?: return false

        // FIX #4: Check if connection is already in progress
        val inProgress = connectionInProgress.computeIfAbsent(deviceAddress) { AtomicBoolean(false) }
        if (!inProgress.compareAndSet(false, true)) {
            Timber.w("Connection already in progress for $deviceAddress, waiting...")
            // Wait for the other connection attempt to finish
            repeat(10) {
                delay(500)
                if (!connectionInProgress.containsKey(deviceAddress)) {
                    // doALittleWet
                }
            }
            // Check cache again after waiting
            val cachedConnection = gattCache[deviceAddress]
            if (cachedConnection != null && cachedConnection.isConnected) {
                return writeViaCachedGatt(deviceAddress, cachedConnection, data)
            }
        }

        try {
            repeat(2) { attempt ->
                Timber.d("Connection attempt ${attempt + 1}/2 for $deviceAddress")
                // FIX #3: Increased timeout to 20s for slower devices
                val result = withTimeoutOrNull(20_000) { connectAndWrite(device, deviceAddress, data) }
                if (result != null) return result
                if (result == false) {
                    Timber.w("Write failed for $deviceAddress (attempt $attempt)")
                    // FIX #3: Increased retry delay, especially for error 133
                    delay(2000)
                } else {
                    Timber.w("Write timed out for $deviceAddress (attempt $attempt)")
                    delay(1000)
                }
            }
            Timber.e("All attempts failed for $deviceAddress")
            return false
        } finally {
            // FIX #4: Clear in-progress flag
            connectionInProgress.remove(deviceAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndWrite(
        device: android.bluetooth.BluetoothDevice,
        deviceAddress: String,
        data: ByteArray
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        if (!connectionSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            Timber.w("Could not acquire semaphore for $deviceAddress")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        var gatt: BluetoothGatt? = null
        val isCompleted = AtomicBoolean(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // ✅ FIX #5: Update last activity time on connection
                    gattCache[deviceAddress]?.touch()
                    // ✅ Запросить высокий приоритет соединения для стабильности
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    try { gatt.requestMtu(BleConstants.MTU_SIZE) }
                    catch (e: SecurityException) { gatt.discoverServices() }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // ✅ FIX #5: Update last activity time on disconnect
                    gattCache[deviceAddress]?.touch()
                    // ✅ Специальная обработка ошибки 133
                    if (status == 133) {
                        Timber.w("GATT_ERROR 133 for $deviceAddress — clearing cache and retrying")
                        gattCache.remove(deviceAddress)
                        connectedDevices.remove(deviceAddress)
                    }

                    gatt.close()
                    connectionSemaphore.release()
                    // FIX #4: Clear in-progress flag on disconnect
                    connectionInProgress.remove(deviceAddress)
                    if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) Timber.d("MTU=$mtu for $deviceAddress")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // ✅ FIX #2 + #4: Защита от дублирования с AtomicBoolean
                val conn = gattCache[deviceAddress]
                if (conn?.isServicesDiscovered == true) {
                    Timber.d("Services already discovered for $deviceAddress, ignoring duplicate callback")
                    return
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(BleConstants.MESH_DATA_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        // FIX #4: Mark discovery as in progress
                        val updatedConn = conn?.copy(isDiscoveryInProgress = true, isServicesDiscovered = true)
                            ?: GattConnection(
                                gatt = gatt,
                                dataCharacteristic = characteristic,
                                isDiscoveryInProgress = true,
                                isServicesDiscovered = true
                            )
                        gattCache[deviceAddress] = updatedConn

                        enableNotifications(gatt, characteristic, deviceAddress) { notificationsEnabled ->
                            val cccDescriptor = characteristic.getDescriptor(BleConstants.CCC_DESCRIPTOR_UUID)
                            gattCache[deviceAddress] = updatedConn.copy(
                                cccDescriptor = cccDescriptor,
                                notificationsEnabled = notificationsEnabled,
                                isDiscoveryInProgress = false,
                                isServicesDiscovered = true,
                                lastActivityTime = System.currentTimeMillis()
                            )
                            characteristic.value = data
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            gatt.writeCharacteristic(characteristic)
                        }
                    } else {
                        Timber.w("Characteristic not found for $deviceAddress")
                        gatt.disconnect()
                        if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
                    }
                } else {
                    Timber.w("Service discovery failed for $deviceAddress, status=$status")
                    gatt.disconnect()
                    if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
                }
            }

            // ✅ FIX #1: Обработка результата записи дескриптора
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == BleConstants.CCC_DESCRIPTOR_UUID) {
                    val address = gatt.device?.address ?: return
                    val success = (status == BluetoothGatt.GATT_SUCCESS)
                    Timber.d("onDescriptorWrite for $address: ${if (success) "SUCCESS" else "FAILED ($status)"}")
                    pendingNotificationCallbacks.remove(address)?.invoke(success)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // FIX #5: Update last activity time on any write event
                gattCache[deviceAddress]?.touch()
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Timber.d("Write successful for $deviceAddress")
                    serviceScope.launch {
                        _sendConfirmations.emit(SendConfirmation("write_${System.currentTimeMillis()}", true, deviceAddress))
                    }
                    connectionSemaphore.release()
                    if (isCompleted.compareAndSet(false, true)) {
                        continuation.resume(true)
                    }
                } else {
                    Timber.e("Write failed for $deviceAddress, status=$status")
                    gatt.disconnect()
                    if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
                }
            }
        }

        try {
            gatt = device.connectGatt(context, false, callback)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLE permissions for connectGatt")
            connectionSemaphore.release()
            if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to $deviceAddress")
            connectionSemaphore.release()
            if (isCompleted.compareAndSet(false, true)) continuation.resume(false)
        }

        continuation.invokeOnCancellation { cause ->
            Timber.w(cause, "connectAndWrite cancelled for $deviceAddress")
            try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { Timber.w(e, "Error closing GATT on cancel") }
            if (isCompleted.compareAndSet(false, true)) connectionSemaphore.release()
        }
    }

    // ✅ FIX #1 + #3 + #4: Полностью переписанная enableNotifications
    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        deviceAddress: String,
        onComplete: (Boolean) -> Unit
    ) {
        val timeoutJob = serviceScope.launch {
            delay(3500) // 3.5 секунды
            Timber.w("enableNotifications TIMEOUT for $deviceAddress")
            pendingNotificationCallbacks.remove(deviceAddress)?.invoke(false)
        }

        try {
            Timber.d("Enabling notifications for $deviceAddress")

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                timeoutJob.cancel()
                Timber.w("setCharacteristicNotification returned false for $deviceAddress")
                onComplete(false)
                return
            }

            val descriptor = characteristic.getDescriptor(BleConstants.CCC_DESCRIPTOR_UUID)
                ?: characteristic.descriptors.find {
                    it.uuid == BleConstants.CCC_DESCRIPTOR_UUID
                }

            if (descriptor == null) {
                timeoutJob.cancel()
                Timber.e("CCC descriptor not found for $deviceAddress")
                onComplete(false)
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            // Защита от race
            pendingNotificationCallbacks[deviceAddress] = onComplete

            serviceScope.launch {
                delay(100) // можно вынести в константу/функцию

                val writeStarted = gatt.writeDescriptor(descriptor)
                Timber.d("writeDescriptor started: $writeStarted for $deviceAddress")

                if (!writeStarted) {
                    timeoutJob.cancel()
                    pendingNotificationCallbacks.remove(deviceAddress)?.invoke(false)
                    onComplete(false)
                }
                // Если writeStarted == true — ждём onDescriptorWrite
            }
        } catch (e: Exception) {
            timeoutJob.cancel()
            Timber.e(e, "Exception in enableNotifications")
            pendingNotificationCallbacks.remove(deviceAddress)?.invoke(false)
            onComplete(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun cacheOutgoingConnection(deviceAddress: String, gatt: BluetoothGatt) {
        serviceScope.launch {
            if (connectedDevices.add(deviceAddress)) {
                Timber.i("Added outgoing connection to $deviceAddress")
                _connectionState.emit(ConnectionState.Connected(deviceAddress))
            }
            val callback = object : BluetoothGattCallback() {
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(BleConstants.MESH_DATA_CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            enableNotifications(gatt, characteristic, deviceAddress) { notificationsEnabled ->
                                val cccDescriptor = characteristic.getDescriptor(BleConstants.CCC_DESCRIPTOR_UUID)
                                gattCache[deviceAddress] = GattConnection(
                                    gatt = gatt,
                                    dataCharacteristic = characteristic,
                                    cccDescriptor = cccDescriptor,
                                    notificationsEnabled = notificationsEnabled,
                                    isServicesDiscovered = true,
                                    lastActivityTime = System.currentTimeMillis() // FIX #5: Initialize last activity time
                                )
                                Timber.d("Cached outgoing connection for $deviceAddress (notifications: $notificationsEnabled)")
                            }
                        } else {
                            Timber.w("Characteristic not found for outgoing connection to $deviceAddress")
                            gatt.disconnect(); gatt.close(); connectionSemaphore.release()
                        }
                    } else {
                        Timber.w("Service discovery failed for outgoing connection to $deviceAddress")
                        gatt.disconnect(); gatt.close(); connectionSemaphore.release()
                    }
                }
            }
            try { gatt.discoverServices() }
            catch (e: SecurityException) { Timber.e(e, "Missing permissions for discoverServices"); connectionSemaphore.release() }
        }
    }

    fun getConnectedDevices(): Set<String> = connectedDevices.toSet()
    fun getConnectedDeviceCount(): Int = connectedDevices.size
    fun areNotificationsEnabled(deviceAddress: String): Boolean = gattCache[deviceAddress]?.notificationsEnabled == true

    @SuppressLint("MissingPermission")
    private suspend fun startAdvertising() {
        assertNotMainThread("startAdvertising")
        withContext(Dispatchers.IO) {
            stopAdvertisingInternal()
            val randomUserName = "b${(1..99).random()}"
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
                Timber.e("BLE advertising not supported"); _advertisingState.emit(false); return@withContext
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            val serviceDataBytes = randomUserName.toByteArray(Charsets.UTF_8)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID), serviceDataBytes)
                .setIncludeTxPowerLevel(false).build()
            val scanResponse = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID)).build()
            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    isAdvertising = true; serviceScope.launch { _advertisingState.emit(true) }
                    Timber.i("Advertising started: $settingsInEffect")
                }
                override fun onStartFailure(errorCode: Int) {
                    isAdvertising = false; serviceScope.launch { _advertisingState.emit(false) }
                    val name = when (errorCode) {
                        1 -> "DATA_TOO_LARGE"; 2 -> "TOO_MANY_ADVERTISERS"; 3 -> "ALREADY_STARTED"
                        4 -> "INTERNAL_ERROR"; 5 -> "FEATURE_UNSUPPORTED"; else -> "UNKNOWN($errorCode)"
                    }
                    Timber.e("Advertising failed: $errorCode ($name)")
                }
            }
            try { advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback!!) }
            catch (e: SecurityException) { Timber.e(e, "Missing ADVERTISE permission"); _advertisingState.emit(false) }
            catch (e: Exception) { Timber.e(e, "Failed to start advertising"); _advertisingState.emit(false) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal() {
        try {
            if (isAdvertising) {
                advertiseCallback?.let { advertiser?.stopAdvertising(it) }
                isAdvertising = false; advertiseCallback = null
            }
        } catch (e: Exception) { Timber.w(e, "Error stopping advertising") }
    }

    // ✅ FIX #5: Упрощённое и надёжное кодирование packetId
    fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray {
        val packetIdBytes: ByteArray = if (packetId.length == 8 && packetId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            // Valid 8-char hex string → decode to 8 bytes
            packetId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            // Any other string → encode as UTF-8, then pad/truncate to 8 bytes
            val utf8Bytes = packetId.toByteArray(Charsets.UTF_8)
            when {
                utf8Bytes.size == 8 -> utf8Bytes
                utf8Bytes.size > 8 -> utf8Bytes.take(8).toByteArray()
                else -> utf8Bytes + ByteArray(8 - utf8Bytes.size) { 0 }
            }
        }

        // Log for debugging bad packetIds
        if (packetId.length != 8 || !packetId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            Timber.w("Non-hex or wrong-length packetId: '$packetId' (len=${packetId.length}), encoding as UTF-8")
        }

        return packetIdBytes + byteArrayOf(ttl.toByte(), hopCount.toByte()) + payload
    }

    @SuppressLint("MissingPermission")
    suspend fun stopService() {
        assertNotMainThread("stopService")
        withContext(Dispatchers.IO) {
            try {
                // FIX #5: Cancel keep-alive job
                keepAliveJob?.cancel()
                keepAliveJob = null
                reconnectAttempts.clear()
                
                if (isAdvertising) {
                    advertiseCallback?.let { advertiser?.stopAdvertising(it) }
                    _advertisingState.tryEmit(false); isAdvertising = false; advertiseCallback = null
                }
                gattCache.values.forEach { try { it.gatt.disconnect(); it.gatt.close() } catch (e: Exception) { Timber.w(e, "Error closing connection") } }
                gattCache.clear(); connectedDevices.clear()
                gattServer?.close(); gattServer = null
                serviceScope.cancel()
                Timber.i("BLE mesh service stopped")
            } catch (e: Exception) { Timber.e(e, "Failed to stop BLE mesh service") }
        }
    }
}