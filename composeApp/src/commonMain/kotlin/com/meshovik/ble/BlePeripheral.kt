package com.meshovik.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Wraps received BLE data with the source device address.
 */
data class ReceivedData(
    val sourceAddress: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedData) return false
        return sourceAddress == other.sourceAddress && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sourceAddress.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Platform-agnostic BLE peripheral interface.
 * Handles GATT server, advertising, and receiving data from connected devices.
 * Implementations use platform-specific BLE peripheral APIs.
 */
expect class BlePeripheral {
    /**
     * Creates a new BLE peripheral with a stable name used in advertising data.
     * @param deviceName stable name advertised in service data.
     */
    constructor(deviceName: String)

    /**
     * Flow for advertising state changes (true = advertising, false = stopped).
     */
    val advertisingState: SharedFlow<Boolean>

    /**
     * Flow for connection state changes.
     */
    val connectionState: SharedFlow<ConnectionState>

    /**
     * Flow for received data from connected devices, including the source address.
     */
    val receivedData: SharedFlow<ReceivedData>

    /**
     * Starts the GATT server and begins advertising.
     * Returns a Flow that emits true when the service is started successfully.
     */
    fun startService(): Flow<Boolean>

    /**
     * Stops the GATT server and advertising.
     */
    fun stopService()

    /**
     * Sends data to a connected device via GATT notification.
     * Returns true if the data was sent successfully.
     */
    fun sendData(deviceAddress: String, data: ByteArray): Boolean

    /**
     * Creates a mesh packet with header (packetId + TTL + hopCount + payload).
     */
    fun createMeshPacket(packetId: String, ttl: Int, hopCount: Int, payload: ByteArray): ByteArray
}

/**
 * Connection state for a BLE device.
 */
sealed class ConnectionState {
    data class Connected(val address: String) : ConnectionState()
    data class Disconnected(val address: String) : ConnectionState()
    data class Connecting(val address: String) : ConnectionState()
}
