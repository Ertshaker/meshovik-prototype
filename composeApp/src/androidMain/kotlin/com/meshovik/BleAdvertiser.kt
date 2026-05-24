package com.meshovik

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.meshovik.BleDevice.Companion.SERVICE_UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

actual class BleAdvertiser(
    private val context: Context
) {

    private val advertiser =
        BluetoothAdapter.getDefaultAdapter().bluetoothLeAdvertiser

    private var _isAdvertising = false

    actual val advertisingState: Flow<Boolean> = callbackFlow {
        trySend(_isAdvertising)
        awaitClose { }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun startAdvertising() {
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                _isAdvertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                _isAdvertising = false
            }
        }

        advertiser.startAdvertising(settings, data, callback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun stopAdvertising() {
        advertiser?.stopAdvertising(callback)
        _isAdvertising = false
    }

    private val callback = object : AdvertiseCallback() {}
}