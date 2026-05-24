package com.meshovik.ble

import com.juul.kable.Peripheral
import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic BLE scanner interface.
 * Implementations use platform-specific BLE scanning APIs.
 */
expect class BleScanner() {
    /**
     * Checks if BLE is supported on this device.
     */
    fun isBleSupported(): Boolean

    /**
     * Checks if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean

    /**
     * Starts scanning for mesh devices and returns a Flow of discovered devices.
     * The scan runs until the flow is cancelled or stopScanning() is called.
     */
    fun scanForDevices(): Flow<Peripheral>

    /**
     * Stops the current scan if running.
     */
    fun stopScanning()
}
