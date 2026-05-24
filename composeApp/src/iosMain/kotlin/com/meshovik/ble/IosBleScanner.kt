package com.meshovik.ble

import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS stub implementation of BLE scanner.
 * TODO: Implement using CBCentralManager
 */
actual class BleScanner {
    private var isScanning = false

    actual fun isBleSupported(): Boolean {
        // TODO: Check CBCentralManager availability when implemented
        return false
    }

    actual fun isBluetoothEnabled(): Boolean {
        // TODO: Check CBCentralManager state when implemented
        return false
    }

    actual fun scanForDevices(): Flow<MeshDevice> {
        println("[IosBleScanner] scanForDevices() - iOS stub, returning empty flow")
        return emptyFlow()
    }

    actual fun stopScanning() {
        isScanning = false
        println("[IosBleScanner] stopScanning() - iOS stub")
    }
}
