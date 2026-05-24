package com.meshovik.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
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
import android.os.ParcelUuid
import com.juul.kable.Peripheral
import com.meshovik.MeshovikApplication
import com.meshovik.core.util.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.UUID

/**
 * Android implementation of BLE peripheral.
 * Handles GATT server, advertising, and receiving data from connected devices.
 */
actual class BlePeripheral actual constructor(
    private val deviceName: String
) {
    private val context get() = MeshovikApplication.context
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var advertiseCallback: AdvertiseCallback? = null

    // Flow for advertising state changes
    private val _advertisingState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    actual val advertisingState: SharedFlow<Boolean> = _advertisingState.asSharedFlow()

    // Flow for received data with source address
    private val _receivedData = MutableSharedFlow<ReceivedData>(extraBufferCapacity = 64)
    actual val receivedData: SharedFlow<ReceivedData> = _receivedData.asSharedFlow()

    // Flow for connection state changes
    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    actual val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    /**
     * Starts the GATT server and begins advertising.
     */
    @SuppressLint("MissingPermission")
    actual fun startService(): Flow<Boolean> = callbackFlow {
        if (bluetoothAdapter == null) {
            Timber.e("Bluetooth adapter is null")
            trySend(false)
            close()
            return@callbackFlow
        }

        try {
            // Start GATT server
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            gattServer?.addService(createMeshService())
            Timber.i("GATT server started with mesh service")

            // Start advertising
            startAdvertising()

            trySend(true)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLE permissions for starting service")
            trySend(false)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start BLE mesh service")
            trySend(false)
        }

        awaitClose {
            stopService()
        }
    }

    /**
     * Creates the custom GATT service with mesh characteristics.
     */
    private fun createMeshService(): BluetoothGattService {
        val service = BluetoothGattService(
            UUID.fromString(BleConstants.MESH_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Data characteristic - for sending/receiving mesh messages
        val dataCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(BleConstants.MESH_DATA_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add CCC descriptor for notifications
        val cccDescriptor = BluetoothGattDescriptor(
            UUID.fromString(BleConstants.CCC_DESCRIPTOR_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        dataCharacteristic.addDescriptor(cccDescriptor)

        // Control characteristic - for mesh control commands
        val controlCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(BleConstants.MESH_CONTROL_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(dataCharacteristic)
        service.addCharacteristic(controlCharacteristic)

        return service
    }

    /**
     * GATT server callback handling client connections and data.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("Device connected: ${device.address}")
                    _connectionState.tryEmit(ConnectionState.Connected(device.address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("Device disconnected: ${device.address}")
                    _connectionState.tryEmit(ConnectionState.Disconnected(device.address))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            if (characteristic.uuid == UUID.fromString(BleConstants.MESH_DATA_CHARACTERISTIC_UUID)) {
                Timber.d("Received data from ${device.address}: ${value.size} bytes")
                processReceivedPacket(value, device.address)

                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) {
                        Timber.e(e, "Missing permission for sendResponse")
                    }
                }
            }
        }
    }

    /**
     * Processes a received packet.
     * Emits the full raw packet to BleManager for consistent parsing/deduplication.
     */
    private fun processReceivedPacket(data: ByteArray, sourceAddress: String) {
        if (data.size < 10) {
            Timber.w("Received packet too small: ${data.size} bytes")
            return
        }

        // Extract packet ID for logging only
        val packetId = data.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }

        // Extract TTL (byte at index 8) for logging
        val ttl = data.getOrNull(8)?.toInt() ?: 0
        if (ttl <= 0) {
            Timber.d("Packet TTL expired: $packetId")
            return
        }

        // Emit the full raw packet with source address.
        // BleManager handles all parsing and deduplication consistently.
        _receivedData.tryEmit(ReceivedData(sourceAddress, data))
        Timber.i("Packet received and emitted: $packetId from $sourceAddress, full packet: ${data.size} bytes")
    }

    /**
     * Starts BLE advertising so other devices can discover and connect.
     */
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Timber.d("startAdvertising() called")

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Timber.e("BLE advertising not supported on this device (bluetoothLeAdvertiser is null)")
            _advertisingState.tryEmit(false)
            return
        }
        
        Timber.d("BluetoothLeAdvertiser obtained successfully")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        Timber.d("AdvertiseSettings built: $settings")

        // ADV_IND packet: minimal advertising data (no device name, no tx power, no service data)
        // Service data is too large for the 31-byte advertising packet limit when combined with 128-bit UUID
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        
        Timber.d("AdvertiseData built successfully (minimal, no service data)")

        // SCAN_RSP packet: service UUID for discoverability
        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(BleConstants.MESH_SERVICE_UUID)))
            .build()
        
        Timber.d("ScanResponse built successfully with service UUID")

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                isAdvertising = true
                _advertisingState.tryEmit(true)
                Timber.i("BLE advertising started successfully, settings: $settingsInEffect")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                isAdvertising = false
                _advertisingState.tryEmit(false)
                val errorName = when (errorCode) {
                    1 -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
                    2 -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
                    3 -> "ADVERTISE_FAILED_ALREADY_STARTED"
                    4 -> "ADVERTISE_FAILED_INTERNAL_ERROR"
                    5 -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN($errorCode)"
                }
                Timber.e("BLE advertising failed with error code: $errorCode ($errorName)")
            }
        }

        try {
            Timber.d("Calling advertiser.startAdvertising()...")
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback!!)
            Timber.d("Advertising start requested, waiting for callback")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLUETOOTH_ADVERTISE permission")
            _advertisingState.tryEmit(false)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start advertising")
            _advertisingState.tryEmit(false)
        }
    }

    /**
     * Sends data to a connected device.
     * @deprecated Outgoing data should be sent via BleCentral (Kable) instead.
     * This peripheral class is primarily a GATT server that receives data.
     * For sending data to devices connected to our GATT server,
     * use GATT notifications via the CCC descriptor.
     */
    @Deprecated(
        message = "Outgoing data should be sent via BleCentral.writeCharacteristic() instead",
        replaceWith = ReplaceWith("bleCentral.writeCharacteristic(deviceAddress, data)", "com.meshovik.ble.BleCentral")
    )
    @SuppressLint("MissingPermission")
    actual fun sendData(deviceAddress: String, data: ByteArray): Boolean {
        // Outgoing data should be handled by BleCentral (Kable).
        // This method is kept for API compatibility but delegates to BleCentral pattern.
        // If you need to notify connected clients via GATT server,
        // implement notifyConnectedDevices() using gattServer?.notifyCharacteristicChanged().
        Timber.w(
            "sendData() called on peripheral - outgoing data should be sent via BleCentral. " +
            "Use BleCentral.writeCharacteristic() for sending to peripherals."
        )
        return false
    }

    /**
     * Creates a mesh packet with header (packetId + TTL + hopCount + payload).
     */
    actual fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray {
        val packetIdBytes = packetId.toByteArray(Charsets.UTF_8).take(8).toByteArray()
        val paddedPacketId = packetIdBytes + ByteArray(8 - packetIdBytes.size) { 0 }
        return paddedPacketId + byteArrayOf(ttl.toByte(), hopCount.toByte()) + payload
    }

    /**
     * Stops the GATT server and advertising.
     */
    @SuppressLint("MissingPermission")
    actual fun stopService() {
        try {
            if (isAdvertising) {
                val callback = advertiseCallback
                if (callback != null) {
                    advertiser?.stopAdvertising(callback)
                    _advertisingState.tryEmit(false)
                    Timber.i("BLE advertising stopped")
                } else {
                    Timber.w("AdvertiseCallback is null, cannot stop advertising")
                }
                isAdvertising = false
                advertiseCallback = null
            }
            gattServer?.close()
            gattServer = null
            Timber.i("GATT server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop BLE mesh service")
        }
    }
}

/**
 * Android-specific factory that creates a Kable [Peripheral] from a MAC address.
 * Now a suspend function - no longer uses runBlocking.
 */
//actual suspend fun createKablePeripheral(address: String): Peripheral? {
//    return try {
//        Logger.d("createKablePeripheral", "Creating peripheral for address: $address")
//        PeripheralBuilder()
////        val adapter = BluetoothAdapter.getDefaultAdapter()
////        if (adapter == null) {
////            Logger.e("createKablePeripheral", "BluetoothAdapter is null")
////            return null
////        }
////        val device = try {
////            adapter.getRemoteDevice(address)
////        } catch (e: IllegalArgumentException) {
////            Logger.e("createKablePeripheral", "Invalid device address: $address", e)
////            return null
////        }
//
//     } catch (e: Exception) {
//        Logger.e("createKablePeripheral", "Failed to create peripheral", e)
//        null
//    }
//}

