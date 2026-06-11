package com.meshovik.transfer

import android.content.Context
import android.net.Uri
import com.meshovik.domain.entity.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import androidx.core.net.toUri

// ─── Константы ──────────────────────────────────────────────────────────────

/** Порт для передачи файлов по Wi-Fi Direct */
private const val FILE_TRANSFER_PORT = 8988

/** Размер буфера чтения/записи */
private const val BUFFER_SIZE = 65536 // 64 KB

/** Таймаут ожидания появления нужного peer в списке (мс) */
private const val PEER_WAIT_TIMEOUT_MS = 20_000L

/** Таймаут ожидания установки P2P соединения (мс) */
private const val CONNECTION_TIMEOUT_MS = 30_000L

/** Максимальное число попыток открыть ServerSocket при EADDRINUSE */
private const val SERVER_SOCKET_BIND_RETRIES = 5

/** Задержка между попытками bind (мс) */
private const val SERVER_SOCKET_BIND_RETRY_DELAY_MS = 1_000L

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
actual class FileTransferManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val wifiDirectManager = WifiDirectManager(context)

    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    actual val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val _incomingTransferRequests = MutableSharedFlow<Attachment>(extraBufferCapacity = 16)
    actual val incomingTransferRequests: Flow<Attachment> = _incomingTransferRequests

    /** Активные ServerSocket для приёма файлов: transferId -> ServerSocket */
    private val serverSockets = mutableMapOf<String, ServerSocket>()

    /**
     * Глобальный ServerSocket, переиспользуемый между передачами.
     * Создаётся один раз при первом вызове [receiveFile] и живёт до [cleanup].
     * Это полностью устраняет EADDRINUSE — порт не освобождается между передачами.
     */
    @Volatile
    private var sharedServerSocket: ServerSocket? = null
    private val serverSocketLock = Any()

    init {
        wifiDirectManager.register()

        // Логируем сообщения пользователю из WifiDirectManager
        scope.launch {
            wifiDirectManager.userMessages.collect { message ->
                Timber.i("WifiDirect → UI: $message")
                // Здесь можно пробросить в UI через отдельный SharedFlow, если нужно
            }
        }

        // Логируем изменения состояния discovery
        scope.launch {
            wifiDirectManager.discoveryState.collect { state ->
                Timber.d("DiscoveryState changed: $state")
            }
        }
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
     *
     * ## Алгоритм
     * 1. Запускаем discovery через [WifiDirectManager.ensureDiscovering]
     * 2. Ждём появления нужного peer (по MAC-адресу) до [PEER_WAIT_TIMEOUT_MS]
     * 3. Если peer не найден — бросаем исключение с понятным сообщением
     * 4. Подключаемся к peer через [WifiDirectManager.connectToPeer]
     * 5. Получаем GroupOwner IP и передаём файл через TCP-сокет
     *
     * @param attachment Метаданные файла
     * @param localUri URI файла на устройстве отправителя
     * @param targetMeshId MAC-адрес Wi-Fi Direct устройства получателя
     * @return transferId при успехе
     */
    actual suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String
    ): String {
        val transferId = attachment.id
        Timber.i("sendFile → transferId=$transferId | file=${attachment.fileName} | target=$targetMeshId")

        updateTransfer(
            FileTransferState(
                transferId = transferId,
                status = FileTransferStatus.PENDING,
                totalBytes = attachment.sizeBytes,
                isSender = true
            )
        )

        try {
            // ── Шаг 1: Убеждаемся, что discovery запущен ──────────────────
            ensureP2pEnabled()

            if (wifiDirectManager.discoveryState.value != DiscoveryState.DISCOVERING) {
                wifiDirectManager.ensureDiscovering()
            }

            // ── Шаг 2: Ждём появления нужного peer ────────────────────────
            val targetPeer = waitForPeer(targetMeshId)
                ?: throw Exception(
                    "Устройство $targetMeshId не найдено за ${PEER_WAIT_TIMEOUT_MS / 1000}с. " +
                    "Убедитесь, что оба устройства находятся рядом и Wi-Fi включён."
                )

            Timber.i("Peer найден: name=${targetPeer.deviceName}, addr=${targetPeer.deviceAddress}")

            wifiDirectManager.removeGroup()

            wifiDirectManager.stopDiscovery() // важно

            // ── Шаг 3: Подключаемся, если ещё не подключены ───────────────
            val groupOwnerAddress = if (wifiDirectManager.isConnected.value) {
                Timber.i("Уже подключены, используем существующее соединение")
                wifiDirectManager.connectionInfo.value?.groupOwnerAddress?.hostAddress
                    ?: throw Exception("Подключены, но GroupOwner IP недоступен")
            } else {
                Timber.i("Подключаемся к ${targetPeer.deviceAddress}...")
                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.PENDING,
                        totalBytes = attachment.sizeBytes,
                        isSender = true
                    )
                )

                val connectionInfo = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                    wifiDirectManager.connectToPeer(targetPeer.deviceAddress)
                } ?: throw Exception("Таймаут подключения к ${targetPeer.deviceAddress} (${CONNECTION_TIMEOUT_MS / 1000}с)")

                connectionInfo.groupOwnerAddress?.hostAddress
                    ?: throw Exception("GroupOwner IP недоступен после подключения")
            }

            Timber.i("GroupOwner IP: $groupOwnerAddress")

            // ── Шаг 4: Передаём файл ──────────────────────────────────────
            updateTransfer(
                FileTransferState(
                    transferId = transferId,
                    status = FileTransferStatus.TRANSFERRING,
                    totalBytes = attachment.sizeBytes,
                    isSender = true
                )
            )

            val uri = localUri.toUri()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Не удалось открыть файл: $localUri")

            sendFileOverSocket(
                transferId = transferId,
                inputStream = inputStream,
                totalBytes = attachment.sizeBytes,
                hostAddress = groupOwnerAddress,
                port = FILE_TRANSFER_PORT
            )

            // ── Шаг 5: Успех ──────────────────────────────────────────────
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

            Timber.i("sendFile: файл успешно отправлен: $transferId")
            return transferId

        } catch (e: Exception) {
            Timber.e(e, "sendFile failed: $transferId")
            updateTransfer(
                FileTransferState(
                    transferId = transferId,
                    status = FileTransferStatus.FAILED,
                    totalBytes = attachment.sizeBytes,
                    errorMessage = e.message,
                    isSender = true
                )
            )
            throw e
        }
    }

    /**
     * Принять входящий файл по Wi-Fi Direct.
     *
     * Использует глобальный [sharedServerSocket], который создаётся один раз
     * и переиспользуется между передачами — это устраняет EADDRINUSE.
     *
     * Если сокет по какой-то причине закрыт — пересоздаёт его с retry.
     */
    actual suspend fun receiveFile(
        transferId: String,
        attachment: Attachment
    ): String {
        Timber.i("receiveFile started: $transferId")

        updateTransfer(
            FileTransferState(
                transferId = transferId,
                status = FileTransferStatus.TRANSFERRING,
                totalBytes = attachment.sizeBytes,
                isSender = false
            )
        )

        return withContext(Dispatchers.IO) {
            var clientSocket: Socket? = null

            try {
                Timber.i("receiveFile: создаём P2P группу (становимся Group Owner)")
                val groupCreated = withTimeoutOrNull(8000) {
                    wifiDirectManager.createGroupSafely()  // новую функцию
                }

                if (groupCreated != true) {
                    throw Exception("Не удалось создать P2P группу")
                }

                Thread.sleep(1500) // даём время группе подняться

                val serverSocket = getOrCreateServerSocket()
                Timber.i("receiveFile: слушаем порт $FILE_TRANSFER_PORT для $transferId")

                clientSocket = serverSocket.accept()
                Timber.i("receiveFile: клиент подключился: ${clientSocket.inetAddress?.hostAddress}")

                val outputFile = createOutputFile(attachment)
                val outputStream = FileOutputStream(outputFile)

                receiveFileFromSocket(transferId, clientSocket, outputStream, attachment.sizeBytes)

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

                Timber.i("receiveFile: файл получен: $transferId → $localUri")
                localUri

            } catch (e: Exception) {
                Timber.e(e, "receiveFile failed: $transferId")
                // Если сокет сломан — сбрасываем его, чтобы следующий вызов пересоздал
                if (e is java.net.SocketException || e is java.io.IOException) {
                    Timber.w("receiveFile: сбрасываем sharedServerSocket из-за ошибки сокета")
                    synchronized(serverSocketLock) {
                        sharedServerSocket?.runCatching { close() }
                        sharedServerSocket = null
                    }
                }
                updateTransfer(
                    FileTransferState(
                        transferId = transferId,
                        status = FileTransferStatus.FAILED,
                        errorMessage = e.message
                    )
                )
                throw e
            } finally {
                // Закрываем только клиентский сокет, НЕ serverSocket
                clientSocket?.runCatching { close() }
            }
        }
    }

    /**
     * Возвращает существующий ServerSocket или создаёт новый.
     * При EADDRINUSE повторяет попытку [SERVER_SOCKET_BIND_RETRIES] раз.
     */
    private fun getOrCreateServerSocket(): ServerSocket {
        synchronized(serverSocketLock) {
            val existing = sharedServerSocket
            if (existing != null && !existing.isClosed) {
                Timber.d("getOrCreateServerSocket: переиспользуем существующий сокет на порту $FILE_TRANSFER_PORT")
                return existing
            }

            Timber.i("getOrCreateServerSocket: создаём новый ServerSocket на порту $FILE_TRANSFER_PORT")
            var lastException: Exception? = null

            repeat(SERVER_SOCKET_BIND_RETRIES) { attempt ->
                try {
                    val socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(FILE_TRANSFER_PORT))
                    }
                    sharedServerSocket = socket
                    Timber.i("getOrCreateServerSocket: успешно создан (попытка ${attempt + 1})")
                    return socket
                } catch (e: java.net.BindException) {
                    lastException = e
                    Timber.w("getOrCreateServerSocket: EADDRINUSE (попытка ${attempt + 1}/$SERVER_SOCKET_BIND_RETRIES) — ждём ${SERVER_SOCKET_BIND_RETRY_DELAY_MS}мс")
                    Thread.sleep(SERVER_SOCKET_BIND_RETRY_DELAY_MS)
                }
            }

            throw lastException ?: Exception("Не удалось создать ServerSocket на порту $FILE_TRANSFER_PORT")
        }
    }

    actual fun cancelTransfer(transferId: String) {
        Timber.i("cancelTransfer: $transferId")

        // Закрываем sharedServerSocket, чтобы прервать accept() в receiveFile
        val currentState = _transfers.value[transferId]
        if (currentState?.status == FileTransferStatus.TRANSFERRING && !currentState.isSender) {
            Timber.i("cancelTransfer: прерываем receiveFile — закрываем sharedServerSocket")
            synchronized(serverSocketLock) {
                sharedServerSocket?.runCatching { close() }
                sharedServerSocket = null
            }
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
            socket.runCatching { close() }
        }
        serverSockets.clear()
        // Закрываем глобальный ServerSocket
        synchronized(serverSocketLock) {
            sharedServerSocket?.runCatching { close() }
            sharedServerSocket = null
        }
        Timber.i("FileTransferManager cleaned up")
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Проверяет, что Wi-Fi P2P включён.
     * Если нет — ждёт включения до 15 секунд.
     */
    private suspend fun ensureP2pEnabled() {
        if (wifiDirectManager.p2pEnabled.value) return

        Timber.w("ensureP2pEnabled: Wi-Fi P2P выключен, ждём...")
        val enabled = withTimeoutOrNull(15_000L) {
            wifiDirectManager.p2pEnabled.first { it }
        }

        if (enabled == null) {
            throw Exception(
                "Wi-Fi P2P недоступен. Включите Wi-Fi на устройстве и повторите попытку."
            )
        }
        Timber.i("ensureP2pEnabled: Wi-Fi P2P включился")
    }

    /**
     * Ждёт появления нужного peer в списке обнаруженных устройств.
     *
     * @param deviceAddress MAC-адрес Wi-Fi Direct устройства
     * @return [WifiP2pDevice] если найден, null если таймаут
     */
    private suspend fun waitForPeer(deviceAddress: String): android.net.wifi.p2p.WifiP2pDevice? {
        Timber.i("waitForPeer: ищем $deviceAddress (таймаут ${PEER_WAIT_TIMEOUT_MS / 1000}с)")

        return withTimeoutOrNull(PEER_WAIT_TIMEOUT_MS) {
            wifiDirectManager.peers.first { list ->
                list.any { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }
            }.find { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }
        }
    }

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
        Timber.i("sendFileOverSocket: подключаемся к $hostAddress:$port")

        Socket().use { socket ->
            socket.soTimeout = 30_000
            socket.connect(InetSocketAddress(InetAddress.getByName(hostAddress), port), 15_000)
            Timber.i("sendFileOverSocket: подключились, начинаем передачу")

            val outputStream: OutputStream = socket.getOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesSent = 0L
            var bytesRead: Int

            inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesSent += bytesRead

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

            Timber.i("sendFileOverSocket: отправлено $bytesSent байт")
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

            Timber.i("receiveFileFromSocket: получено $bytesReceived байт")
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
