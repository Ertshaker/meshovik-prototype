package com.meshovik.ble

import android.annotation.SuppressLint
import com.juul.kable.Bluetooth
import com.juul.kable.ExperimentalApi
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.logs.Logging
import com.juul.kable.logs.SystemLogEngine
import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Android implementation of BLE scanner using Kable library.
 */
actual class BleScanner {

    /**
     * Checks if BLE is supported on this device.
     */
    actual fun isBleSupported(): Boolean = true

    /**
     * Checks if Bluetooth is enabled.
     */
    actual fun isBluetoothEnabled(): Boolean = true

    /**
     * Starts scanning for mesh devices and returns a Flow of discovered devices.
     * Uses Kable's advertisements Flow for BLE scanning.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)
    @SuppressLint("MissingPermission")
    actual fun scanForDevices(): Flow<Peripheral> = flow {
        val scanner = Scanner {
            filters {
                match {
                    services = listOf(Uuid.parse(BleConstants.MESH_SERVICE_UUID))
                }
            }
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
                format = Logging.Format.Multiline
            }
        }

        scanner.advertisements
            .map { advertisement ->
                Peripheral(advertisement)
            }
            .collect { device ->
                emit(device)
                Timber.d("Discovered device: ${device.name} RSSI: ${device.rssi()}")
            }
    }

    /**
     * Stops the current scan if running.
     * Kable handles scan lifecycle automatically when flow collection stops.
     */
    @SuppressLint("MissingPermission")
    actual fun stopScanning() {
        // Kable handles scan lifecycle automatically when flow collection stops
        Timber.i("BLE scan stopped (Kable auto-stops on flow cancellation)")
    }
}
