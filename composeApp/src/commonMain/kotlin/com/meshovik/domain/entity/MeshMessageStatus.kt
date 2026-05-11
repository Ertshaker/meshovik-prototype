package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

/**
 * Status of a mesh message in the network.
 */
@Serializable
enum class MeshMessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}
