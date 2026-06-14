package com.meshovik

import com.juul.kable.Advertisement
import com.juul.kable.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

expect class BleDevice(advertisement: Advertisement) {

    val mtu: StateFlow<Int?>

    suspend fun connect()
    suspend fun disconnect()

    @OptIn(ExperimentalUuidApi::class)
    suspend fun write(data: ByteArray)

    @OptIn(ExperimentalUuidApi::class)
    fun observe(): Flow<ByteArray>

    companion object {
        val SERVICE_UUID: String
        val CHAR_UUID: String
    }
}