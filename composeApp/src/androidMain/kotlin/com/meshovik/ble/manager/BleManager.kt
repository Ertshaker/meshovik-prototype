package com.meshovik.ble.manager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.meshovik.ble.model.BleConstants
import com.meshovik.ble.scanner.BleScanner
import com.meshovik.ble.service.BleMeshService
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class BleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bleScanner = BleScanner(context)
    private val bleMeshService = BleMeshService(context)

    private val outgoingConnections = mutableSetOf<String>()
    private val handshakeDeferred = mutableMapOf<String, Long>()

    // ==================== STATE FLOWS ====================
    private val _discoveredDevices = MutableStateFlow<List<MeshDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshDevice>> = _discoveredDevices.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    // FIX #7: SharedFlow for individual message events (avoids index-based tracking)
    private val _receivedMessageEvents = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val receivedMessageEvents: SharedFlow<MeshMessage> = _receivedMessageEvents.asSharedFlow()

    // FIX #1: StateFlow<Set<String>> for reliable connection checking
    private val _connectedNeighbors = MutableStateFlow<Set<String>>(emptySet())
    val connectedNeighbors: StateFlow<Set<String>> = _connectedNeighbors.asStateFlow()

    // Keep count for backward compatibility
    private val _connectedNeighborsCount = MutableStateFlow(0)
    val connectedNeighborsCount: StateFlow<Int> = _connectedNeighborsCount.asStateFlow()

    private val _disconnectedNeighbors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val disconnectedNeighbors = _disconnectedNeighbors.asSharedFlow()

    private var localDeviceAddress: String = "unknown"
    private var scanRestartJob: Job? = null
    private var handshakeTimeoutJob: Job? = null

    init {
        initializeLocalDeviceInfo()
        observeServiceEvents()
    }

    @SuppressLint("HardwareIds", "MissingPermission") // ← Вот это главное
    private fun initializeLocalDeviceInfo() {
        try {
            val adapter = bluetoothManager.adapter
            localDeviceAddress = adapter?.address ?: "unknown_${System.currentTimeMillis().toString(16).takeLast(8)}"
        } catch (e: SecurityException) {
            localDeviceAddress = "unknown_${System.currentTimeMillis().toString(16).takeLast(8)}"
        }
        Timber.i("Local device address: $localDeviceAddress")
    }

    private fun observeServiceEvents() {
        scope.launch {
            bleMeshService.connectionState.collect { state ->
                // FIX #1: Update both Set and count from single source of truth
                val connectedDevices = bleMeshService.getConnectedDevices()
                _connectedNeighbors.value = connectedDevices
                _connectedNeighborsCount.value = connectedDevices.size

                if (state is BleMeshService.ConnectionState.Disconnected) {
                    outgoingConnections.remove(state.address)
                    _disconnectedNeighbors.emit(state.address)
                }
            }
        }

        scope.launch {
            bleMeshService.receivedData.collect { packet ->
                parseReceivedPacket(packet)?.let { message ->
                    _receivedMessages.update { it + message }
                    // FIX #7: Emit individual message events
                    _receivedMessageEvents.emit(message)
                }
            }
        }
    }

    fun startMeshService(): Flow<Boolean> = bleMeshService.startService()

    @SuppressLint("MissingPermission")   // ← Вот это главное
    fun stopMeshService() {
        scope.launch {
            try {
                bleMeshService.stopService()   // теперь внутри coroutine
            } catch (e: Exception) {
                Timber.e(e, "Error stopping mesh service")
            }
        }
        _connectedNeighborsCount.value = 0
    }

    // ==================== SCANNING ====================
    fun startScanning() {
        scanRestartJob?.cancel()

        startScanInternal()

        // FIX #3: Increased scan restart interval + only restart when needed
        scanRestartJob = scope.launch {
            while (true) {
                delay(BleConstants.SCAN_RESTART_INTERVAL_MS)
                val connectionCount = bleMeshService.getConnectedDeviceCount()
                // Only restart scan if we have few connections AND need to discover more
                if (connectionCount < BleConstants.SCAN_RESTART_MIN_CONNECTIONS) {
                    Timber.d("Periodic scan restart (connections=$connectionCount < ${BleConstants.SCAN_RESTART_MIN_CONNECTIONS})")
                    bleScanner.stopScanning()
                    delay(800)
                    startScanInternal()
                } else {
                    Timber.d("Scan restart skipped (connections=$connectionCount, sufficient)")
                }
            }
        }
    }

    private fun startScanInternal() {
        scope.launch {
            bleScanner.scanForDevices().collect { device ->
                val address = device.address
                if (address == localDeviceAddress) return@collect

                updateDiscoveredDevices(device)

                if (isAlreadyConnected(address)) return@collect

                handleHandshake(address)
            }
        }
    }

    private fun updateDiscoveredDevices(device: MeshDevice) {
        _discoveredDevices.update { current ->
            val index = current.indexOfFirst { it.address == device.address }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = device }
            } else {
                current + device
            }
        }
    }

    private fun isAlreadyConnected(address: String): Boolean {
        return bleMeshService.getConnectedDevices().contains(address) || outgoingConnections.contains(address)
    }

    /**
     * FIX #2: Check if device has an incoming connection (connected to our GATT server).
     * If so, we should not initiate an outgoing connection to avoid collision.
     */
    private fun hasIncomingConnection(address: String): Boolean {
        return bleMeshService.getConnectedDevices().contains(address)
    }

    private fun handleHandshake(address: String) {
        // FIX #2: Skip handshake if already connected (either incoming or outgoing)
        if (isAlreadyConnected(address)) {
            Timber.d("Already connected to $address, skipping handshake")
            return
        }

        // FIX #2: If device has incoming connection, don't initiate outgoing connection
        // This prevents connection collisions that cause GATT error 133
        if (hasIncomingConnection(address)) {
            Timber.d("Device $address has incoming connection, skipping outgoing connection")
            return
        }

        if (address > localDeviceAddress) {
            if (!handshakeDeferred.containsKey(address)) {
                handshakeDeferred[address] = System.currentTimeMillis()
                Timber.d("Deferred connection to $address (remote has higher address)")
                
                // FIX #4: Start timeout job to prevent deadlock if remote never connects
                startHandshakeTimeoutCheck(address)
            }
            return
        }
        connectToDevice(address)
    }

    /**
     * FIX #2 + #4: Check deferred handshakes and connect if timeout exceeded.
     * Prevents deadlock when both devices defer to each other.
     * Increased timeout from 10s to 15s to give more time for remote device to connect.
     */
    private fun startHandshakeTimeoutCheck(address: String) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            // FIX #2: Increased timeout to 15s
            delay(15_000L)
            val deferTime = handshakeDeferred[address] ?: return@launch
            val elapsed = System.currentTimeMillis() - deferTime
            
            if (elapsed >= 15_000L) {
                // FIX #2: Check if incoming connection was established during wait
                if (hasIncomingConnection(address)) {
                    Timber.d("Incoming connection established for $address during defer timeout")
                    handshakeDeferred.remove(address)
                    return@launch
                }
                // Timeout exceeded - connect anyway to break deadlock
                if (!isAlreadyConnected(address)) {
                    Timber.w("Handshake timeout for $address (${elapsed}ms), initiating connection to break deadlock")
                    handshakeDeferred.remove(address)
                    connectToDevice(address)
                }
            }
        }
    }

    private fun checkDeferredHandshakes() {
        val now = System.currentTimeMillis()
        val timedOut = handshakeDeferred.filterValues { startTime ->
            now - startTime >= 15_000L  // FIX #2: Increased timeout to 15s
        }.keys
        
        for (address in timedOut) {
            // FIX #2: Check if incoming connection was established during wait
            if (hasIncomingConnection(address)) {
                Timber.d("Incoming connection established for $address during defer timeout")
                handshakeDeferred.remove(address)
                continue
            }
            if (!isAlreadyConnected(address)) {
                Timber.w("Deferred handshake timeout for $address, connecting to break deadlock")
                handshakeDeferred.remove(address)
                connectToDevice(address)
            } else {
                handshakeDeferred.remove(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceAddress: String) {
        if (outgoingConnections.contains(deviceAddress)) return

        outgoingConnections.add(deviceAddress)
        Timber.i("Initiating outgoing GATT to $deviceAddress")

        try {
            val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
            val callback = createGattCallback(deviceAddress)
            // TRANSPORT_LE explicitly
            device.connectGatt(context, false, callback, 2) // 2 = TRANSPORT_LE
        } catch (e: SecurityException) {
            Timber.e(e, "Permission error connecting to $deviceAddress")
            outgoingConnections.remove(deviceAddress)
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to $deviceAddress")
            outgoingConnections.remove(deviceAddress)
        }
    }

    @SuppressLint("MissingPermission")   // ← Вот это главное
    private fun createGattCallback(deviceAddress: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("GATT Connected → $deviceAddress")
                    gatt.requestMtu(BleConstants.MTU_SIZE)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.w("GATT Disconnected from $deviceAddress (status=$status)")
                    outgoingConnections.remove(deviceAddress)
                    gatt.close()
                    scope.launch { _disconnectedNeighbors.emit(deviceAddress) }
                }
            }
        }

        @SuppressLint("MissingPermission")   // ← Вот это главное
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) Timber.d("MTU = $mtu for $deviceAddress")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Services discovered for $deviceAddress")
                bleMeshService.cacheOutgoingConnection(deviceAddress, gatt)
            } else {
                Timber.e("Service discovery failed for $deviceAddress")
                gatt.disconnect()
            }
        }
    }

    fun stopScanning() {
        scanRestartJob?.cancel()
        scanRestartJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        bleScanner.stopScanning()
        handshakeDeferred.clear()
    }

    suspend fun sendData(targetAddress: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        bleMeshService.sendData(targetAddress, data)
    }

    fun broadcastToAllNeighbors(packetData: ByteArray) {
        scope.launch {
            bleMeshService.broadcastToAllNeighbors(packetData)
        }
    }

    /**
     * Creates a mesh packet using the underlying BleMeshService.
     * Delegates packet creation to the service layer.
     */
    fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray {
        return bleMeshService.createMeshPacket(packetId, ttl, hopCount, payload)
    }

    fun getConnectedNeighbors(): Set<String> = bleMeshService.getConnectedDevices()

    fun getLocalAddress(): String = localDeviceAddress

    /**
     * Returns the local device name (or fallback to address).
     */
    fun getLocalName(): String {
        return "Mesh-${localDeviceAddress.takeLast(4)}"
    }

    /**
     * Returns whether scanning is currently active.
     */
    val isScanning: StateFlow<Boolean>
        get() = bleScanner.isScanningState

    /**
     * Returns whether advertising is currently active.
     */
    val isAdvertising: StateFlow<Boolean>
        get() = bleMeshService.advertisingStateAsStateFlow

    /**
     * Returns connection states as a StateFlow map.
     */
    val connectionStates: StateFlow<Map<String, BleMeshService.ConnectionState>>
        get() = _connectionStatesMap

    private val _connectionStatesMap = MutableStateFlow<Map<String, BleMeshService.ConnectionState>>(emptyMap())

    init {
        // Observe connection state changes for UI
        scope.launch {
            bleMeshService.connectionState.collect { state ->
                when (state) {
                    is BleMeshService.ConnectionState.Connected -> {
                        _connectionStatesMap.update { map ->
                            map + (state.address to state)
                        }
                    }
                    is BleMeshService.ConnectionState.Disconnected -> {
                        _connectionStatesMap.update { map ->
                            map + (state.address to state)
                        }
                    }
                    is BleMeshService.ConnectionState.Connecting -> {
                        _connectionStatesMap.update { map ->
                            map + (state.address to state)
                        }
                    }
                }
            }
        }
    }

    private fun parseReceivedPacket(receivedPacket: BleMeshService.ReceivedPacket): MeshMessage? {
        return try {
            // ← Вставь здесь свою старую реализацию парсинга
            // Я оставил заглушку — замени на свой код
            null // TODO: replace with your original parseReceivedPacket logic
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse packet")
            null
        }
    }

    fun cleanup() {
        stopScanning()
        stopMeshService()
        scope.cancel()
    }
}