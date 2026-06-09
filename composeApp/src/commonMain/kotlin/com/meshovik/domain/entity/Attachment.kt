package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

/**
 * Метаданные вложения, передаваемые по BLE.
 * Сам файл передаётся отдельно через Wi-Fi Direct.
 *
 * @param id          Уникальный ID вложения (совпадает с transferId в FileTransferManager)
 * @param type        Тип вложения
 * @param fileName    Оригинальное имя файла
 * @param mimeType    MIME-тип (image/jpeg, audio/ogg и т.д.)
 * @param sizeBytes   Размер файла в байтах
 * @param localUri    Локальный URI файла (заполняется после получения/выбора, не передаётся по BLE)
 * @param thumbnailBase64 Миниатюра в Base64 (опционально, для изображений, передаётся по BLE)
 * @param width       Ширина изображения в пикселях (для IMAGE/VIDEO)
 * @param height      Высота изображения в пикселях (для IMAGE/VIDEO)
 * @param durationMs  Длительность в миллисекундах (для VOICE/VIDEO)
 */
@Serializable
data class Attachment(
    val id: String,
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localUri: String? = null,
    val thumbnailBase64: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)
