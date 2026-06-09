package com.meshovik.transfer

import android.content.Context
import android.net.Uri
import com.meshovik.domain.entity.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Порт для передачи файлов по Wi-Fi Direct */
private const val FILE_TRANSFER_PORT = 8988
/** Размер буфера чтения/записи */
private const val BUFFER_SIZE = 65536 // 64 KB

/**
 * Android-реализация FileTransferManager через Wi-Fi Direct (WifiP2p).
 *
 * Протокол передачи:
 * 1. Отправитель: BLE сигнализирует получателю метаданные (Attachment)
 * 2. Получатель: вызывает receiveFile() — открывает ServerSocket
 * 3. Отправитель: вызывает sendFile() — подключается к GroupOwner IP:PORT
 * 4. Данные передаются потоком, прогресс обновляется через StateFlow
 */
actual class FileTransferManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val wifiDirectManager = WifiDirectManager(context)

    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    actual val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val _incomingTransferRequests = MutableSharedFlow<Attachment>(extraBufferCapacity = 16)
    actual val incomingTransferRequests: Flow<Attachment> = _incomingTransferRequests

    /** Активные ServerSocket для приёма файлов: transferId -> ServerSocket */
    private val serverSockets = mutableMapOf<String, ServerSocket>()

    init {
        wifiDirectManager.register()
    }

    /**
     * Уведомить менеджер о входящем запросе на передачу файла (вызывается из BleManager).
     * Это позволяет UI показать "Получаем изображение..." и запустить receiveFile().
     */
    fun notifyIncomingTransfer(attachment: Attachment) {
        scope.launch {
            _incomingTransferRequests.emit(attachment)
            updateTransfer(
                FileTransferState(
                    transferId = attachment.id,
                    status = FileTransferStatus.PENDING,
                    totalBytes = attachment.sizeBytes,
                    isSender = false
                )
            )
            Timber.i("Incoming transfer notified: ${attachment.id} (${attachment.fileName})")
        }
    }

    /**
     * Отправить файл по Wi-Fi Direct.
     * Предполагается, что Wi-Fi Direct соединение уже установлено (или будет установлено).
     */
    actual suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String
    ): String {
        val transferId = attachment.id
        Timber.i("sendFile: transferId=$transferId, file=${attachment.fileName}, target=$targetMeshId")

        updateTransfer(
            FileTransferState(
                transferId = transferId,
                status = FileTransferStatus.PENDING,
                totalBytes = attachment.sizeBytes,
                isSender = true
            )
        )

        scope.launch {
            try {
                // Ждём информацию о Wi-Fi Direct соединении
                val connectionInfo = wifiDirectManager.connectionInfo.value
                    ?: run {
                        Timber.w("No Wi-Fi Direct connection info, starting peer discovery...")
                        wifiDirectManager.discoverPeers()
                        // Ждём соединения через StateFlow
                        var info = wifiDirectManager.connectionInfo.value
                        var attempts = 0
                        while (info == null && attempts < 30) {
                            kotlinx.coroutines.delay(500)
                            info = wifiDirectManager.connectionInfo.value
                            attempts++
                        }
                        info ?: throw Exception("Wi-Fi Direct connection not established")
                    }

                val groupOwnerAddress = connectionInfo.groupOwnerAddress?.hostAddress
                    ?: throw Exception("Group owner address is null")

                Timber.i("Connecting to GroupOwner: $groupOwnerAddress:$FILE_TRANSFER_PORT")

                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.TRANSFERRING,
                        totalBytes = attachment.sizeBytes,
                        isSender = true
                    )
                )

                // Открываем URI и отправляем файл
                val uri = Uri.parse(localUri)
                val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file: $localUri")

                sendFileOverSocket(
                    transferId = transferId,
                    inputStream = inputStream,
                    totalBytes = attachment.sizeBytes,
                    hostAddress = groupOwnerAddress,
                    port = FILE_TRANSFER_PORT
                )

                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.COMPLETED,
                        progressBytes = attachment.sizeBytes,
                        totalBytes = attachment.sizeBytes,
                        localUri = localUri,
                        isSender = true
                    )
                )
                Timber.i("✅ File sent successfully: $transferId")

            } catch (e: Exception) {
                Timber.e(e, "❌ sendFile failed: $transferId")
                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.FAILED,
                        totalBytes = attachment.sizeBytes,
                        errorMessage = e.message,
                        isSender = true
                    )
                )
            }
        }

        return transferId
    }

    /**
     * Принять входящий файл по Wi-Fi Direct.
     * Открывает ServerSocket и ждёт подключения отправителя.
     */
    actual suspend fun receiveFile(
        transferId: String,
        attachment: Attachment
    ): String {
        Timber.i("receiveFile: transferId=$transferId, file=${attachment.fileName}")

        updateTransfer(
            FileTransferState(
                transferId = transferId,
                status = FileTransferStatus.TRANSFERRING,
                totalBytes = attachment.sizeBytes,
                isSender = false
            )
        )

        return withContext(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(FILE_TRANSFER_PORT)
                serverSockets[transferId] = serverSocket

                Timber.i("ServerSocket listening on port $FILE_TRANSFER_PORT for $transferId")

                val clientSocket = serverSocket.accept()
                serverSockets.remove(transferId)

                val outputFile = createOutputFile(attachment)
                val outputStream = FileOutputStream(outputFile)

                receiveFileFromSocket(
                    transferId = transferId,
                    socket = clientSocket,
                    outputStream = outputStream,
                    totalBytes = attachment.sizeBytes
                )

                val localUri = Uri.fromFile(outputFile).toString()

                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.COMPLETED,
                        progressBytes = attachment.sizeBytes,
                        totalBytes = attachment.sizeBytes,
                        localUri = localUri,
                        isSender = false
                    )
                )

                Timber.i("✅ File received successfully: $transferId -> $localUri")
                localUri

            } catch (e: Exception) {
                Timber.e(e, "❌ receiveFile failed: $transferId")
                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.FAILED,
                        totalBytes = attachment.sizeBytes,
                        errorMessage = e.message,
                        isSender = false
                    )
                )
                throw e
            }
        }
    }

    actual fun cancelTransfer(transferId: String) {
        Timber.i("Cancelling transfer: $transferId")
        serverSockets[transferId]?.let { socket ->
            try {
                socket.close()
            } catch (e: Exception) {
                Timber.w(e, "Error closing server socket for $transferId")
            }
            serverSockets.remove(transferId)
        }
        updateTransfer(
            _transfers.value[transferId]?.copy(status = FileTransferStatus.CANCELLED)
                ?: FileTransferState(transferId = transferId, status = FileTransferStatus.CANCELLED)
        )
    }

    actual fun getTransferState(transferId: String): FileTransferState? {
        return _transfers.value[transferId]
    }

    actual fun cleanup() {
        serverSockets.values.forEach { socket ->
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
        serverSockets.clear()
        wifiDirectManager.unregister()
        Timber.i("FileTransferManager cleaned up")
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private fun updateTransfer(state: FileTransferState) {
        _transfers.update { current ->
            current + (state.transferId to state)
        }
    }

    private suspend fun sendFileOverSocket(
        transferId: String,
        inputStream: InputStream,
        totalBytes: Long,
        hostAddress: String,
        port: Int
    ) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(hostAddress), port), 10_000)
            val outputStream: OutputStream = socket.getOutputStream()

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesSent = 0L
            var bytesRead: Int

            inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesSent += bytesRead

                    // Обновляем прогресс
                    updateTransfer(
                        FileTransferState(
                            transferId = transferId,
                            status = FileTransferStatus.TRANSFERRING,
                            progressBytes = bytesSent,
                            totalBytes = totalBytes,
                            isSender = true
                        )
                    )
                }
                outputStream.flush()
            }

            Timber.d("sendFileOverSocket: sent $bytesSent bytes")
        }
    }

    private suspend fun receiveFileFromSocket(
        transferId: String,
        socket: Socket,
        outputStream: FileOutputStream,
        totalBytes: Long
    ) = withContext(Dispatchers.IO) {
        socket.use { s ->
            val inputStream = s.getInputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesReceived = 0L
            var bytesRead: Int

            outputStream.use { output ->
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesReceived += bytesRead

                    // Обновляем прогресс
                    updateTransfer(
                        FileTransferState(
                            transferId = transferId,
                            status = FileTransferStatus.TRANSFERRING,
                            progressBytes = bytesReceived,
                            totalBytes = totalBytes,
                            isSender = false
                        )
                    )
                }
            }

            Timber.d("receiveFileFromSocket: received $bytesReceived bytes")
        }
    }

    /**
     * Создаёт файл для сохранения принятого вложения.
     * Сохраняет в app-specific external storage (не требует разрешений).
     */
    private fun createOutputFile(attachment: Attachment): File {
        val dir = File(context.getExternalFilesDir(null), "MeshTransfers").also { it.mkdirs() }
        return File(dir, "${attachment.id}_${attachment.fileName}")
    }
}
