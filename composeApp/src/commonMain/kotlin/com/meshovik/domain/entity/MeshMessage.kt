package com.meshovik.domain.entity

import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Type of mesh message.
 */
@Serializable
enum class MessageType {
    TEXT,
    ACK,
    HEARTBEAT,
    FLOOD,
    /** Сообщение содержит вложение; тяжёлый контент передаётся по Wi-Fi Direct */
    ATTACHMENT
}

/**
 * Represents a message in the BLE Mesh network.
 * Для сообщений с вложением (type == ATTACHMENT):
 *  - content содержит текстовое описание / подпись (может быть пустым)
 *  - attachment содержит метаданные файла
 *  - сам файл передаётся по Wi-Fi Direct (attachment.id == transferId)
 */
@Serializable
data class MeshMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,

    @Contextual
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val status: MeshMessageStatus = MeshMessageStatus.PENDING,
    val ttl: Int = DEFAULT_TTL,
    val hopCount: Int = 0,

    /** Метаданные вложения. Не null только когда type == ATTACHMENT */
    val attachment: Attachment? = null
) {
    companion object {
        const val DEFAULT_TTL = 5
    }
}
