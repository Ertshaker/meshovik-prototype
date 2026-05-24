package com.meshovik

import kotlinx.coroutines.flow.Flow

expect class BleAdvertiser {
    val advertisingState: Flow<Boolean>
    fun startAdvertising()
    fun stopAdvertising()
}
