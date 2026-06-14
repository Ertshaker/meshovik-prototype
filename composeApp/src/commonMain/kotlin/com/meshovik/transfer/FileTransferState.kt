package com.meshovik.transfer
import androidx.compose.ui.graphics.ImageBitmap
import com.meshovik.domain.entity.Attachment

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
    val isSender: Boolean = true,
    val partialImageBytes: ByteArray? = null,   // текущие собранные байты
) {
    /** Прогресс от 0.0 до 1.0 */
    val progress: Float
        get() = if (totalBytes > 0) (progressBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isFinished: Boolean
        get() = status == FileTransferStatus.COMPLETED ||
                status == FileTransferStatus.FAILED ||
                status == FileTransferStatus.CANCELLED

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FileTransferState

        if (progressBytes != other.progressBytes) return false
        if (totalBytes != other.totalBytes) return false
        if (isSender != other.isSender) return false
        if (transferId != other.transferId) return false
        if (status != other.status) return false
        if (localUri != other.localUri) return false
        if (errorMessage != other.errorMessage) return false
        if (!partialImageBytes.contentEquals(other.partialImageBytes)) return false
        if (progress != other.progress) return false
        if (isFinished != other.isFinished) return false

        return true
    }

    override fun hashCode(): Int {
        var result = progressBytes.hashCode()
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + isSender.hashCode()
        result = 31 * result + transferId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (localUri?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (partialImageBytes?.contentHashCode() ?: 0)
        result = 31 * result + progress.hashCode()
        result = 31 * result + isFinished.hashCode()
        return result
    }


}