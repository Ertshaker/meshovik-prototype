package com.meshovik.domain.entity

import kotlinx.serialization.Serializable

/**
 * Тип вложения в сообщении.
 * BLE передаёт только метаданные, сам файл — через Wi-Fi Direct.
 */
@Serializable
sealed class AttachmentType {

    /** Изображение (JPEG, PNG, WebP и т.д.) */
    @Serializable
    data object IMAGE : AttachmentType()

    /** Видеофайл */
    @Serializable
    data object VIDEO : AttachmentType()

    /** Голосовое сообщение */
    @Serializable
    data object VOICE : AttachmentType()

    /** Произвольный файл */
    @Serializable
    data object FILE : AttachmentType()
}
