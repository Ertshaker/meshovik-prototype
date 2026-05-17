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

    private val _connectedNeighborsCount = MutableStateFlow(0)
    val connectedNeighborsCount: StateFlow<Int> = _connectedNeighborsCount.asStateFlow()

    private val _disconnectedNeighbors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val disconnectedNeighbors = _disconnectedNeighbors.asSharedFlow()

    private var localDeviceAddress: String = "unknown"
    private var scanRestartJob: Job? = null

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
                _connectedNeighborsCount.value = bleMeshService.getConnectedDeviceCount()

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

        // Мягкий рестарт
        scanRestartJob = scope.launch {
            while (true) {
                delay(25_000)
                if (bleMeshService.getConnectedDeviceCount() <= 1) {
                    Timber.d("Periodic scan restart (low connections)")
                    bleScanner.stopScanning()
                    delay(800)
                    startScanInternal()
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

    private fun handleHandshake(address: String) {
        if (address > localDeviceAddress) {
            if (!handshakeDeferred.containsKey(address)) {
                handshakeDeferred[address] = System.currentTimeMillis()
                Timber.d("Deferred connection to $address (remote has higher address)")
            }
            return
        }
        connectToDevice(address)
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

    fun getConnectedNeighbors(): Set<String> = bleMeshService.getConnectedDevices()

    fun getLocalAddress(): String = localDeviceAddress

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