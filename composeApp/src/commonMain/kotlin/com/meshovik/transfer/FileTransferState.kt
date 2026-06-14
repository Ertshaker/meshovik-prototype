package com.meshovik.transfer

/**
 * Статус передачи файла.
 */
enum class FileTransferStatus {
    /** Ожидает начала передачи */
    PENDING,
    /** Идёт передача */
    TRANSFERRING,
    /** Передача завершена успешно */
    COMPLETED,
    /** Ошибка передачи */
    FAILED,
    /** Передача отменена */
    CANCELLED
}

/**
 * Состояние конкретной передачи файла.
 *
 * @param transferId    Уникальный ID передачи (совпадает с Attachment.id)
 * @param status        Текущий статус
 * @param progressBytes Количество переданных байт
 * @param totalBytes    Общий размер файла в байтах
 * @param localUri      Локальный URI файла (заполняется после завершения приёма)
 * @param errorMessage  Сообщение об ошибке (если status == FAILED)
 * @param isSender      true — мы отправляем, false — принимаем
 */
data class FileTransferState(
    val transferId: String,
    val status: FileTransferStatus = FileTransferStatus.PENDING,
    val progressBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val localUri: String? = null,
    val errorMessage: String? = null,
    val isSender: Boolean = true
) {
    /** Прогресс от 0.0 до 1.0 */
    val progress: Float
        get() = if (totalBytes > 0) (progressBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isFinished: Boolean
        get() = status == FileTransferStatus.COMPLETED ||
                status == FileTransferStatus.FAILED ||
                status == FileTransferStatus.CANCELLED
}
