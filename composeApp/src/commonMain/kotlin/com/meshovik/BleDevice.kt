package com.meshovik

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class BleDevice(
    private val advertisement: Advertisement
) {

    private val peripheral = Peripheral(advertisement)

    suspend fun connect() {
        peripheral.connect()
    }

    suspend fun disconnect() {
        peripheral.disconnect()
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun write(data: ByteArray) {
        peripheral.write(
            characteristic = characteristicOf(
                service = Uuid.parse(SERVICE_UUID),
                characteristic = Uuid.parse(CHAR_UUID)
            ),
            data = data,
            writeType = WriteType.WithResponse
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    fun observe(): Flow<ByteArray> {
        return peripheral.observe(
            characteristicOf(
                service = Uuid.parse(SERVICE_UUID),
                characteristic = Uuid.parse(CHAR_UUID)
            )
        )
    }

    companion object {
        const val SERVICE_UUID = "A1B2C3D4-E5F6-7890-A1B2-C3D4E5F67890"
        const val CHAR_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
    }
}