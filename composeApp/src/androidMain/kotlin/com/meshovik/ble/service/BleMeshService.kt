package com.meshovik.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
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
import android.os.ParcelUuid
import com.meshovik.ble.model.BleConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.UUID

/**
 * BLE Mesh Service - the core GATT service for the mesh network.
 * Handles advertising as a peripheral and connecting as a central.
 * Implements a simple flooding-based mesh with TTL and deduplication.
 */
class BleMeshService(
    private val context: Context
) {
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    // Flow for received data
    private val _receivedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ByteArray> = _receivedData.asSharedFlow()

    // Flow for connection state changes
    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    // Deduplication cache: packetId -> timestamp
    private val seenPackets = mutableMapOf<String, Long>()

    /**
     * Connection state for a BLE device.
     */
    sealed class ConnectionState {
        data class Connected(val address: String) : ConnectionState()
        data class Disconnected(val address: String) : ConnectionState()
        data class Connecting(val address: String) : ConnectionState()
    }

    /**
     * Starts the GATT server and begins advertising.
     */
    @SuppressLint("MissingPermission")
    fun startService(): Flow<Boolean> = callbackFlow {
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
            BleConstants.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Data characteristic - for sending/receiving mesh messages
        val dataCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MESH_DATA_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add CCC descriptor for notifications
        val cccDescriptor = BluetoothGattDescriptor(
            BleConstants.CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        dataCharacteristic.addDescriptor(cccDescriptor)

        // Control characteristic - for mesh control commands
        val controlCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MESH_CONTROL_CHARACTERISTIC_UUID,
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

            if (characteristic.uuid == BleConstants.MESH_DATA_CHARACTERISTIC_UUID) {
                Timber.d("Received data from ${device.address}: ${value.size} bytes")
                processReceivedPacket(value)

                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) {
                        Timber.e(e, "Missing permission for sendResponse")
                    }
                }
            }
        }
    }

    /**
     * Processes a received packet, handling deduplication and flooding.
     */
    private fun processReceivedPacket(data: ByteArray) {
        if (data.size < 8) {
            Timber.w("Received packet too small: ${data.size} bytes")
            return
        }

        // Extract packet ID (first 8 bytes as hex string)
        val packetId = data.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }

        // Check for duplicate
        val now = System.currentTimeMillis()
        if (seenPackets.containsKey(packetId)) {
            Timber.d("Duplicate packet ignored: $packetId")
            return
        }

        // Add to seen packets and clean old entries
        seenPackets[packetId] = now
        cleanDeduplicationCache()

        // Extract TTL (byte at index 8)
        val ttl = data.getOrNull(8)?.toInt() ?: 0
        if (ttl <= 0) {
            Timber.d("Packet TTL expired: $packetId")
            return
        }

        // Emit received data (skip header: 8 bytes packetId + 1 byte TTL + 1 byte hopCount)
        val payload = data.sliceArray(10 until data.size)
        _receivedData.tryEmit(payload)
        Timber.i("Packet received and emitted: $packetId, payload: ${payload.size} bytes")
    }

    /**
     * Cleans old entries from the deduplication cache.
     */
    private fun cleanDeduplicationCache() {
        val now = System.currentTimeMillis()
        seenPackets.entries.removeAll { (id, timestamp) ->
            now - timestamp > BleConstants.DEDUPLICATION_WINDOW_MS
        }
    }

    /**
     * Starts BLE advertising so other devices can discover and connect.
     */
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Timber.e("BLE advertising not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                isAdvertising = true
                Timber.i("BLE advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                isAdvertising = false
                Timber.e("BLE advertising failed with error code: $errorCode")
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start advertising")
        }
    }

    /**
     * Sends data to a connected device via GATT notification.
     */
    @SuppressLint("MissingPermission")
    fun sendData(deviceAddress: String, data: ByteArray): Boolean {
        val device = try {
            bluetoothAdapter?.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Timber.e("Invalid device address: $deviceAddress")
            return false
        } ?: return false

        // For MVP: we'll use a simple approach - connect, write, disconnect
        // In production, you'd maintain persistent connections
        return try {
            val gatt = device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(BleConstants.MESH_DATA_CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            characteristic.value = data
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            gatt.writeCharacteristic(characteristic)
                            Timber.d("Data sent to $deviceAddress: ${data.size} bytes")
                        }
                        gatt.disconnect()
                    }
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.d("Write successful for ${gatt.device.address}")
                    } else {
                        Timber.e("Write failed with status: $status")
                    }
                    gatt.disconnect()
                }
            })
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLE permissions for sending data")
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to send data to $deviceAddress")
            false
        }
    }

    /**
     * Creates a mesh packet with header (packetId + TTL + hopCount + payload).
     */
    fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray {
        val packetIdBytes = packetId.toByteArray(Charsets.UTF_8).take(8).toByteArray()
        val paddedPacketId = packetIdBytes + ByteArray(8 - packetIdBytes.size) { 0 }
        return paddedPacketId + byteArrayOf(ttl.toByte(), hopCount.toByte()) + payload
    }

    /**
     * Stops the GATT server and advertising.
     */
    @SuppressLint("MissingPermission")
    fun stopService() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(object : AdvertiseCallback() {})
                isAdvertising = false
                Timber.i("BLE advertising stopped")
            }
            gattServer?.close()
            gattServer = null
            Timber.i("GATT server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop BLE mesh service")
        }
    }
}
