package com.meshovik

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

actual class BleScanner(
    private val context: Context
) {
    private var isScanning = false

    @OptIn(ExperimentalUuidApi::class)
    actual fun scan(): Flow<Advertisement> {
        isScanning = true
        return Scanner {
//            filters {
//                match {
//                    services = listOf(Uuid.parse("A1B2C3D4-E5F6-7890-A1B2-C3D4E5F67890"))
//                }
//            }
        }.advertisements
    }

    actual fun scanForDevices(): Flow<MeshDevice> {
        return scan().map { advertisement ->
            Timber.w("Skibidi найден: ${advertisement.identifier}")
            advertisement.toMeshDevice()
        }
    }

    actual fun isBleSupported(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter != null
    }

    actual fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }

    actual fun stopScanning() {
        isScanning = false
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun Advertisement.toMeshDevice(): MeshDevice {
        return MeshDevice(
            id = this.identifier,
            name = this.name ?: "Unknown",
            address = this.identifier,
            rssi = this.rssi,
            lastSeen = Clock.System.now(),
            isOnline = true,
            hopCount = 0
        )
    }
}
