package com.meshovik.transfer

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.net.toUri
import coil3.toCoilUri
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import coil3.toAndroidUri
import com.meshovik.ble.manager.BleManager
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


// ─── Константы ──────────────────────────────────────────────────────────────

/**
 * Android-реализация FileTransferManager через Wi-Fi Direct (WifiP2p).
 *
 * ## Протокол передачи
 * 1. Отправитель: BLE сигнализирует получателю метаданные (Attachment)
 * 2. Получатель: вызывает [receiveFile] — открывает ServerSocket
 * 3. Отправитель: вызывает [sendFile] — подключается к GroupOwner IP:PORT
 * 4. Данные передаются потоком, прогресс обновляется через StateFlow
 *
 * ## Важно о Wi-Fi Direct
 * - `targetMeshId` в контексте Wi-Fi Direct — это MAC-адрес Wi-Fi Direct устройства
 * - GroupOwner — устройство, которое создаёт P2P группу и имеет известный IP
 * - Не-GroupOwner подключается к GroupOwner по его IP
 */

actual class FileTransferManager(
    private val context: Context,
    private val bleManager: BleManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    actual val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val activeTransfers = ConcurrentHashMap<String, TransferSession>()

    init {
        observeFileChunks()
    }

    private fun observeFileChunks() {
        scope.launch {
            bleManager.fileChunksReceived.collect { (transferId, chunk) ->
                onImageDataReceived(transferId, chunk)
            }
        }
    }
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    actual suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String,
    ): String {
        val transferId = attachment.id ?: UUID.randomUUID().toString()

        return try {
            updateTransferState(
                transferId = transferId,
                status = FileTransferStatus.TRANSFERRING,
                totalBytes = attachment.sizeBytes,
                isSender = true,
            )
            val bytes = context.contentResolver.openInputStream(localUri.toUri())?.use {
                it.readBytes()
            } ?: throw IllegalStateException("Не удалось прочитать изображение")

            activeTransfers[transferId] = TransferSession(attachment, isSender = true)
            val message = bleManager.sendMessageWithAttachment(targetMeshId, attachment)
            delay(1500)
            sendImageData(transferId, targetMeshId, bytes)

            transferId
        } catch (e: Exception) {
            Timber.e(e, "sendFile failed")
            updateTransferState(transferId, FileTransferStatus.FAILED, error = e.message)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendImageData(transferId: String, targetAddress: String, data: ByteArray) {
        val payloadChunks = bleManager.bleChunker.chunk(data)   // только payload

        Timber.i("Sending image $transferId → ${payloadChunks.size} chunks (${data.size} bytes), chunkSize=${bleManager.bleChunker.chunkSize}")

        payloadChunks.forEachIndexed { index, payload ->
            val fullPacket = createFileChunkPacket(transferId, payload)  // +17 байт

            val sent = bleManager.sendFilePacket(targetAddress, fullPacket)   // ← новый метод ниже

            if (!sent) {
                Timber.w("Failed to send chunk $index")
            }
        }

        updateTransferState(transferId, FileTransferStatus.COMPLETED, totalBytes = data.size.toLong())
    }

    fun onImageDataReceived(transferId: String, packet: ByteArray) {
        val session = activeTransfers[transferId] ?: return

        session.receivedBytes += packet

        val progress = session.receivedBytes.size.toLong()

        updateTransferState(
            transferId = transferId,
            status = FileTransferStatus.TRANSFERRING,
            progressBytes = progress,
            totalBytes = session.attachment.sizeBytes,
            isSender = false
        )
        Timber.i("File successfully saved: $progress ${session.attachment.sizeBytes}")
        if (progress >= session.attachment.sizeBytes) {
            completeReceiving(transferId, session)
        }
    }

    private fun completeReceiving(transferId: String, session: TransferSession) {
        scope.launch(Dispatchers.IO) {
            try {
                Timber.i("Final image size: ${session.receivedBytes.size}, expected: ${session.attachment.sizeBytes}")
                val file = saveToFile(transferId, session.receivedBytes, session.attachment.fileName)
                val localUri = file.toUri().toString()

                updateTransferState(
                    transferId = transferId,
                    status = FileTransferStatus.COMPLETED,
                    localUri = localUri
                )

                Timber.i("File successfully saved: $localUri")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save received file")
                updateTransferState(transferId, FileTransferStatus.FAILED, error = e.message)
            } finally {
                activeTransfers.remove(transferId)
            }
        }
    }

    fun startReceiving(transferId: String, attachment: Attachment, senderAddress: String) {
        activeTransfers.getOrPut(transferId) {
            TransferSession(
                attachment = attachment.copy(sizeBytes = attachment.sizeBytes), // важно!
                isSender = false
            )
        }

        // Можно сразу обновить UI-состояние
        updateTransferState(
            transferId = transferId,
            status = FileTransferStatus.TRANSFERRING,
            totalBytes = attachment.sizeBytes,
            isSender = false,
        )
        Timber.i("Started receiving file $transferId from $senderAddress (${attachment.sizeBytes} bytes)")
    }
    private fun saveToFile(transferId: String, bytes: ByteArray, fileName: String): File {
        Timber.i("СОБИРАЮ НАХУЙ ИЗОБРАЖЕНИЕ!!!")
        val dir = File(context.getExternalFilesDir(null), "MeshImages").apply { mkdirs() }
        val file = File(dir, "${transferId}_$fileName")
        file.writeBytes(bytes)
        return file
    }

    // ====================== COMMON ======================
    private fun updateTransferState(
        transferId: String,
        status: FileTransferStatus,
        progressBytes: Long = 0L,
        totalBytes: Long = 0L,
        isSender: Boolean = false,
        localUri: String? = null,
        error: String? = null,
    ) {
        _transfers.update { current ->
            val existing = current[transferId]
            current + (transferId to FileTransferState(
                transferId = transferId,
                status = status,
                progressBytes = progressBytes,
                totalBytes = totalBytes.coerceAtLeast(existing?.totalBytes ?: 0L),
                isSender = isSender,
                localUri = localUri,
                errorMessage = error,
            ))
        }
    }

    private fun createFileChunkPacket(transferId: String, data: ByteArray): ByteArray {
        val type = byteArrayOf(0xF1.toByte()) // IMAGE_CHUNK
        val idBytes = transferId.toByteArray(Charsets.UTF_8).copyOf()
        return type + idBytes + data
    }

    actual fun cancelTransfer(transferId: String) {
        activeTransfers.remove(transferId)
        updateTransferState(transferId, FileTransferStatus.CANCELLED)
    }

    actual fun getTransferState(transferId: String): FileTransferState? = _transfers.value[transferId]

    actual fun cleanup() {
        activeTransfers.clear()
        _transfers.value = emptyMap()
    }
}

data class TransferSession(
    val attachment: Attachment,
    val isSender: Boolean,
    var receivedBytes: ByteArray = ByteArray(0),
    var job: Job? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransferSession

        if (isSender != other.isSender) return false
        if (attachment != other.attachment) return false
        if (!receivedBytes.contentEquals(other.receivedBytes)) return false
        if (job != other.job) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isSender.hashCode()
        result = 31 * result + attachment.hashCode()
        result = 31 * result + receivedBytes.contentHashCode()
        result = 31 * result + (job?.hashCode() ?: 0)
        return result
    }

}



