package com.meshovik

import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.Flow

expect class BleScanner {
    fun scan(): Flow<Advertisement>
    fun isBleSupported(): Boolean
    fun isBluetoothEnabled(): Boolean
    fun stopScanning()
}
