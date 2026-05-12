package com.meshovik.ble.model

import java.util.UUID

/**
 * BLE Mesh constants including custom GATT service and characteristic UUIDs.
 */
object BleConstants {
    // Custom GATT Service UUID for Meshovik Mesh Network
    val MESH_SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-A1B2-C3D4E5F67890")

    // Characteristic UUIDs
    val MESH_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    val MESH_CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")

    // Client Characteristic Configuration Descriptor UUID
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Scanning settings
    const val SCAN_DURATION_MS = 10_000L
    const val SCAN_INTERVAL_MS = 15_000L

    // Connection settings
    const val CONNECTION_TIMEOUT_MS = 30_000L
    const val MTU_SIZE = 512

    // Mesh settings
    const val DEFAULT_TTL = 5
    const val MAX_HOP_COUNT = 10
    const val DEDUPLICATION_WINDOW_MS = 30_000L
}
