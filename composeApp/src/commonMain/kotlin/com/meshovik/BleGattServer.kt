package com.meshovik

import kotlinx.coroutines.flow.Flow

expect class BleGattServer {

    fun start()
    fun stop()

    val receivedData: Flow<ByteArray>
}