package com.meshovik.transfer

import com.juul.kable.Filter
import com.meshovik.domain.entity.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Менеджер передачи файлов через Wi-Fi Direct.
 *
 * Архитектура:
 * - BLE используется только для сигнализации (метаданные вложения в MeshMessage)
 * - Wi-Fi Direct используется для передачи самого файла
 *
 * expect/actual: commonMain объявляет интерфейс, androidMain реализует через WifiP2pManager.
 */
expect class FileTransferManager {

    /**
     * Flow всех активных и завершённых передач.
     * Key = transferId (совпадает с Attachment.id)
     */
    val transfers: StateFlow<Map<String, FileTransferState>>

    /**
     * Инициировать отправку файла по Wi-Fi Direct.
     *
     * @param attachment  Метаданные файла (id, type, fileName, mimeType, sizeBytes)
     * @param localUri    Локальный URI файла на устройстве-отправителе
     * @param targetMeshId MeshID получателя (для поиска Wi-Fi Direct peer)
     * @return transferId (совпадает с attachment.id)
     */
    suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String
    ): String

    /**
     * Отменить передачу.
     */
    fun cancelTransfer(transferId: String)

    /**
     * Получить состояние конкретной передачи.
     */
    fun getTransferState(transferId: String): FileTransferState?

    /**
     * Освободить ресурсы.
     */
    fun cleanup()
}