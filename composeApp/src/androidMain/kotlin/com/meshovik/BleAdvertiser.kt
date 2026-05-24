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
import java.util.UUID

actual class BleAdvertiser(
    private val context: Context
) {

    private val advertiser =
        BluetoothAdapter.getDefaultAdapter().bluetoothLeAdvertiser

    actual fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .setIncludeDeviceName(true)
            .build()

        advertiser.startAdvertising(settings, data, callback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun stopAdvertising() {
        advertiser.stopAdvertising(callback)
    }

    private val callback = object : AdvertiseCallback() {}
}