package com.meshovik.domain.entity

import kotlinx.serialization.Serializable
import kotlin.time.Clock
@Serializable
data class MeshControlMessage(
    val type: ControlMessageType,
    val attachmentId: String?,
    val senderId: String,
    val receiverId: String,
    val userName: String? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

enum class ControlMessageType {
    READY_FOR_TRANSFER,
    USER_INFO,
    REQUEST_USER_INFO
}