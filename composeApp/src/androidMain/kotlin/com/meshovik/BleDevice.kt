package com.meshovik

import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

actual class BleDevice actual constructor(
    private val advertisement: Advertisement
) {

    private val peripheral = Peripheral(advertisement) {
        onServicesDiscovered {
            try {
                // Можно попробовать 247 (более стабильный) или 512
                val negotiatedMtu = requestMtu(512)
                Timber.i("✅ MTU negotiated: $negotiatedMtu bytes for ${advertisement.identifier}")

            } catch (e: Exception) {
                Timber.w(e, "MTU negotiation failed for ${advertisement.identifier}")
            }
        }
    }

    actual val mtu: StateFlow<Int?>
        get() = (peripheral as AndroidPeripheral).mtu

    actual suspend fun connect() {
        try {
            peripheral.connect()
            Timber.Forest.i("Connected to ${advertisement.identifier}")
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to connect to ${advertisement.identifier}")
            throw e
        }
    }

    actual suspend fun disconnect() {
        try {
            peripheral.disconnect()
            Timber.Forest.i("Disconnected from ${advertisement.identifier}")
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to disconnect")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    actual suspend fun write(data: ByteArray) {
        try {
            peripheral.write(
                characteristic = characteristicOf(
                    service = Uuid.Companion.parse(SERVICE_UUID),
                    characteristic = Uuid.Companion.parse(CHAR_UUID)
                ),
                data = data,
                writeType = WriteType.WithResponse
            )
        } catch (e: Exception) {
            Timber.Forest.e(e, "Write failed")
            throw e
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    actual fun observe(): Flow<ByteArray> {
        return peripheral.observe(
            characteristicOf(
                service = Uuid.Companion.parse(SERVICE_UUID),
                characteristic = Uuid.Companion.parse(CHAR_UUID)
            )
        )
    }

    actual companion object {
        actual const val SERVICE_UUID = "a1b2c3d4-e5f6-7890-a1b2-c3d4e5f67890"
        actual const val CHAR_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
    }
}