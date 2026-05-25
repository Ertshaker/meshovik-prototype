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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID

actual class BleAdvertiser(
    private val context: Context
) {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser

    private val _advertisingState = MutableStateFlow(false)
    actual val advertisingState: Flow<Boolean> = _advertisingState.asStateFlow()

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            _advertisingState.value = true
            Timber.i("BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            _advertisingState.value = false
            val errorName = when (errorCode) {
                1 -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
                2 -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
                3 -> "ADVERTISE_FAILED_ALREADY_STARTED"
                4 -> "ADVERTISE_FAILED_INTERNAL_ERROR"
                5 -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Timber.e("BLE advertising failed with error: $errorCode ($errorName)")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun startAdvertising() {
        if (advertiser == null) {
            Timber.e("BLE advertising not supported (bluetoothLeAdvertiser is null)")
            _advertisingState.value = false
            return
        }

        if (_advertisingState.value) {
            Timber.w("Advertising already started, skipping")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Use scan response for device name (separate 31-byte packet)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        Timber.d("Starting BLE advertising...")
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun stopAdvertising() {
        advertiser?.stopAdvertising(callback)
        _advertisingState.value = false
        Timber.d("BLE advertising stopped")
    }
}
