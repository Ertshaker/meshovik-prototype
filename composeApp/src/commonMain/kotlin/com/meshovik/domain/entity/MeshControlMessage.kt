package com.meshovik.domain.entity

import kotlinx.serialization.Serializable
import kotlin.time.Clock
@Serializable
data class MeshControlMessage(
    val type: ControlMessageType,
    val attachmentId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

enum class ControlMessageType {
    READY_FOR_TRANSFER,
    // позже можно добавить CANCEL_TRANSFER, ACK и т.д.
}