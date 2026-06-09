package com.meshovik

import android.Manifest
import android.bluetooth.*
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.util.UUID

actual class BleGattServer (
    private val context: Context
) {

    private var gattServer: BluetoothGattServer? = null
    private val _receivedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    actual val receivedData: Flow<ByteArray> = _receivedData

    private val serviceUuid = UUID.fromString(BleDevice.SERVICE_UUID)
    private val charUuid = UUID.fromString(BleDevice.CHAR_UUID)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    actual fun start() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return

        gattServer = bluetoothManager.openGattServer(context, GattServerCallback())

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // CCCD (Client Characteristic Configuration Descriptor) is required by Kable for observe()
        // Without it, Kable throws "Characteristic is missing config descriptor"
        val cccd = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)

        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        Timber.i("GATT Server started")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    actual fun stop() {
        gattServer?.close()
        gattServer = null
        Timber.i("GATT Server stopped")
    }

    private inner class GattServerCallback : BluetoothGattServerCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == charUuid && value != null) {
                Timber.i("Received ${value.size} bytes from ${device.address}")

                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )

                _receivedData.tryEmit(value)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == cccdUuid) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
                Timber.i("CCCD subscription handled for ${device.address}")
            }
        }
    }
}