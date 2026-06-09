package com.meshovik.transfer

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
     * Flow входящих запросов на приём файла.
     * Эмитирует Attachment с метаданными, полученными по BLE.
     */
    val incomingTransferRequests: Flow<Attachment>

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
     * Принять входящий файл.
     *
     * @param transferId  ID передачи (из Attachment.id, полученного по BLE)
     * @param attachment  Метаданные файла
     * @return Локальный URI сохранённого файла
     */
    suspend fun receiveFile(
        transferId: String,
        attachment: Attachment
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
