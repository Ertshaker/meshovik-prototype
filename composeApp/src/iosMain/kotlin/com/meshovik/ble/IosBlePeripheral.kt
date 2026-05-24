package com.meshovik.ble

import com.juul.kable.Peripheral
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * iOS stub implementation of BLE peripheral.
 * TODO: Implement using CBPeripheralManager
 */
actual class BlePeripheral actual constructor(
    private val deviceName: String
) {
    private val _advertisingState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    actual val advertisingState: SharedFlow<Boolean> = _advertisingState.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    actual val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    private val _receivedData = MutableSharedFlow<ReceivedData>(extraBufferCapacity = 64)
    actual val receivedData: SharedFlow<ReceivedData> = _receivedData.asSharedFlow()

    actual fun startService(): Flow<Boolean> {
        println("[IosBlePeripheral] startService() - iOS stub, returning false")
        return flow { emit(false) }
    }

    actual fun stopService() {
        println("[IosBlePeripheral] stopService() - iOS stub")
    }

    actual fun sendData(deviceAddress: String, data: ByteArray): Boolean {
        println("[IosBlePeripheral] sendData() - iOS stub, returning false")
        return false
    }

    actual fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray {
        println("[IosBlePeripheral] createMeshPacket() - iOS stub")
        return payload
    }
}

/**
 * iOS stub for Kable peripheral factory.
 */
actual suspend fun createKablePeripheral(address: String): Peripheral? = null
