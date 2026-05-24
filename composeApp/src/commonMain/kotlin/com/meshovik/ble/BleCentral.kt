package com.meshovik.ble

import com.juul.kable.Bluetooth
import com.juul.kable.Bluetooth.Availability.Available
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.meshovik.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * BLE Central implementation using Kable.
 * Handles connecting, writing, and reading from peripherals.
 *
 * NOTE: Kable 0.33.0 requires platform-specific peripheral creation.
 * This implementation provides a scaffold that should be completed
 * with platform-specific Scanner/Peripheral adapters.
 */
class BleCentral {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapMutex = Mutex()
    private val _connectionState = MutableSharedFlow<CentralConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<CentralConnectionState> = _connectionState.asSharedFlow()

    private val _receivedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ByteArray> = _receivedData.asSharedFlow()

    private val peripherals = mutableMapOf<String, Peripheral>()
    private val peripheralJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val peripheralsMutex = Mutex()
    // Per-device locks for connection to avoid blocking other devices during connection waits
    private val connectionLocks = mutableMapOf<String, Mutex>()

    private val meshServiceUuid = BleConstants.MESH_SERVICE_UUID
    private val meshDataCharacteristicUuid = BleConstants.MESH_DATA_CHARACTERISTIC_UUID

    /**
     * Connects to a peripheral device.
     * Uses platform-specific [createKablePeripheral] to create the Kable [Peripheral],
     * then connects and observes state changes.
     */
//    fun connect(deviceAddress: String): Flow<CentralConnectionState> = flow {
//        Logger.i("BleCentral", "connect() called for $deviceAddress")
//        if (peripherals.containsKey(deviceAddress)) {
//            Logger.d("BleCentral", "Peripheral already registered: $deviceAddress")
//            emit(CentralConnectionState.AlreadyConnected(deviceAddress))
//            return@flow
//        }
//
//        Logger.d("BleCentral", "Creating Kable peripheral for $deviceAddress")
//        val peripheral = createKablePeripheral(deviceAddress)
//        if (peripheral == null) {
//            Logger.e("BleCentral", "Failed to create Kable peripheral for $deviceAddress")
//            emit(CentralConnectionState.Error(deviceAddress, "Failed to create peripheral"))
//            return@flow
//        }
//
//        registerPeripheral(deviceAddress, peripheral)
//        emit(CentralConnectionState.Connecting(deviceAddress))
//
//        try {
//            Logger.d("BleCentral", "Connecting to peripheral: $deviceAddress")
//            peripheral.connect()
//            // Wait for connection with timeout
//            withTimeoutOrNull(10000) {
//                peripheral.state.first { it is State.Connected }
//            } ?: run {
//                Logger.w("BleCentral", "Connection timeout for $deviceAddress")
//                emit(CentralConnectionState.Error(deviceAddress, "Connection timeout"))
//                return@flow
//            }
//            Logger.i("BleCentral", "Connected to $deviceAddress")
//            emit(CentralConnectionState.Connected(deviceAddress))
//        } catch (e: Exception) {
//            Logger.e("BleCentral", "Connection failed for $deviceAddress", e)
//            emit(CentralConnectionState.Error(deviceAddress, e.message ?: "Connection failed"))
//        }
//    }
    suspend fun getDeviceMutex(address: String): Mutex {
        return mapMutex.withLock {
            connectionLocks.getOrPut(address) { Mutex() }
        }
    }
    /**
     * Connects and writes data in one shot. Creates the peripheral if needed.
     * Waits for connection before writing.
     * Thread-safe: uses per-device locks to avoid blocking connections to other devices.
     */
    suspend fun connectAndWrite(device: Peripheral, data: ByteArray): Boolean {
        Logger.i("BleCentral", "connectAndWrite() called: target=${device.identifier}, data=${data.size} bytes")

        val deviceLock = getDeviceMutex(device.identifier.toString())

        return deviceLock.withLock {
            // Ensure connected
            if (!ensureConnected(device, device.identifier.toString())) {
                return@withLock false
            }

            Logger.d("BleCentral", "Calling writeCharacteristic for ${device.identifier}")
            return@withLock writeCharacteristicUnlocked(device.identifier.toString(), data)
        }
    }

    /**
     * Ensures the peripheral is connected, connecting if necessary.
     * Must be called under the device lock.
     */
    private suspend fun ensureConnected(peripheral: Peripheral, deviceAddress: String): Boolean {
        Logger.d("BleCentral", "Peripheral state: ${peripheral.state.value}")
        if (peripheral.state.value is State.Connected) {
            return true
        }
        
        Logger.d("BleCentral", "Peripheral not connected, connecting: $deviceAddress")
        try {
            peripheral.connect()
            Logger.d("BleCentral", "Waiting for connection: $deviceAddress")
            withTimeoutOrNull(5000) {
                peripheral.state.first { it is com.juul.kable.State.Connected }
            } ?: run {
                Logger.w("BleCentral", "Connection timeout for $deviceAddress")
                return false
            }
            Logger.i("BleCentral", "Connected to $deviceAddress")
            return true
        } catch (e: Exception) {
            Logger.e("BleCentral", "Connection failed for $deviceAddress", e)
            return false
        }
    }

    /**
     * Writes data to a connected device.
     */
    suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        return peripheralsMutex.withLock {
            writeCharacteristicUnlocked(deviceAddress, data)
        }
    }

    /**
     * Internal write method that assumes caller holds the mutex lock.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeCharacteristicUnlocked(deviceAddress: String, data: ByteArray): Boolean {
        Logger.d("BleCentral", "writeCharacteristicUnlocked() called: target=$deviceAddress, data=${data.size} bytes")
        val peripheral = peripherals[deviceAddress] ?: run {
            Logger.e("BleCentral", "Peripheral not found: $deviceAddress")
            return false
        }

        return try {
            val characteristic = characteristicOf(
                Uuid.parse(meshServiceUuid),
                Uuid.parse(
                    meshDataCharacteristicUuid
                )
            )
            Logger.d("BleCentral", "Writing to characteristic: $meshServiceUuid / $meshDataCharacteristicUuid")
            peripheral.write(
                characteristic = characteristic,
                data = data,
                writeType = WriteType.WithoutResponse
            )
            Logger.i("BleCentral", "Data sent to $deviceAddress: ${data.size} bytes")
            true
        } catch (e: Exception) {
            Logger.e("BleCentral", "Failed to send data to $deviceAddress", e)
            false
        }
    }

    /**
     * Disconnects from a peripheral device.
     */
    suspend fun disconnect(deviceAddress: String) {
        Logger.d("BleCentral", "disconnect() called for $deviceAddress")
        peripheralJobs.remove(deviceAddress)?.cancel()
        val peripheral = peripherals.remove(deviceAddress)
        if (peripheral != null) {
            try {
                peripheral.disconnect()
                _connectionState.emit(CentralConnectionState.Disconnected(deviceAddress))
                Logger.i("BleCentral", "Disconnected from $deviceAddress")
            } catch (e: Exception) {
                Logger.e("BleCentral", "Failed to disconnect from $deviceAddress", e)
            }
        } else {
            _connectionState.emit(CentralConnectionState.Disconnected(deviceAddress))
            Logger.d("BleCentral", "Peripheral already removed: $deviceAddress")
        }
    }

    /**
     * Disconnects from all peripheral devices in parallel.
     */
    suspend fun disconnectAll() = coroutineScope {
        peripherals.keys.toList().forEach { address ->
            launch { disconnect(address) }
        }
    }

    /**
     * Cleans up all resources and disconnects from all peripherals.
     * Note: Does not cancel internal scope - caller should manage lifecycle.
     */
    suspend fun cleanup() {
        Logger.i("BleCentral", "cleanup() called - disconnecting all peripherals")
        disconnectAll()
        Logger.i("BleCentral", "All peripherals disconnected")
    }

    /**
     * Fully destroys the BleCentral instance and cancels all internal coroutines.
     * Call this when the instance is no longer needed (e.g., on app exit).
     */
    fun destroy() {
        Logger.i("BleCentral", "destroy() called - cancelling internal scope")
        scope.cancel()
    }

    /**
     * Gets the list of connected peripheral addresses.
     */
    fun getConnectedAddresses(): Set<String> = peripherals.keys.toSet()

    /**
     * Registers a peripheral for a given address.
     * This allows platform-specific code to create peripherals and register them here.
     */
    fun registerPeripheral(address: String, peripheral: Peripheral) {
        peripherals[address] = peripheral
        Logger.i("BleCentral", "Peripheral registered: $address")
        
        // Start observing state changes
        val job = scope.launch {
            try {
                peripheral.state.collect { kableState ->
                    val centralState = when (kableState) {
                        is com.juul.kable.State.Connecting -> CentralConnectionState.Connecting(address)
                        is com.juul.kable.State.Connected -> CentralConnectionState.Connected(address)
                        is com.juul.kable.State.Disconnecting -> CentralConnectionState.Disconnecting(address)
                        is com.juul.kable.State.Disconnected -> {
                            Logger.d("BleCentral", "Peripheral disconnected: $address (removing from map)")
                            peripherals.remove(address)
                            peripheralJobs.remove(address)?.cancel()
                            CentralConnectionState.Disconnected(address)
                        }
                        else -> CentralConnectionState.Disconnected(address)
                    }
                    _connectionState.emit(centralState)
                }
            } catch (e: Exception) {
                Logger.e("BleCentral", "State collection failed for $address", e)
            }
        }
        peripheralJobs[address] = job
    }
}

/**
 * Central connection state.
 */
sealed class CentralConnectionState {
    data class Connecting(val address: String) : CentralConnectionState()
    data class Connected(val address: String) : CentralConnectionState()
    data class AlreadyConnected(val address: String) : CentralConnectionState()
    data class Disconnecting(val address: String) : CentralConnectionState()
    data class Disconnected(val address: String) : CentralConnectionState()
    data class Error(val address: String, val message: String) : CentralConnectionState()
}
