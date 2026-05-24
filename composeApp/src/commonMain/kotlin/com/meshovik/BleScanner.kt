package com.meshovik

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

expect class BleScanner {
    fun scan(): Flow<Advertisement>
    fun scanForDevices(): Flow<MeshDevice>
    fun isBleSupported(): Boolean
    fun isBluetoothEnabled(): Boolean
    fun stopScanning()
}
