package com.meshovik.domain.entity

import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a device in the BLE Mesh network.
 */
@Serializable
data class MeshDevice(
    val id: String,
    val name: String,
    val address: String,
    val wifiDirectAddress: String?,
    val rssi: Int = 0,

    @Contextual
    val lastSeen: Instant = Instant.DISTANT_PAST,

    val isOnline: Boolean = false,
    val hopCount: Int = 0,

    /**
     * Stable Mesh ID (e.g. "MeshA1B2C3D4") — known after receiving first message from this device.
     * Empty string if not yet known.
     */
    val meshId: String = ""
)
