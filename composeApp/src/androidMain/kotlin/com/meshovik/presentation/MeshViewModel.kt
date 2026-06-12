package com.meshovik.presentation

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.meshovik.ble.manager.BleManager
import com.meshovik.data.repository.MeshRepository
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.ControlMessageType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.domain.entity.MessageType
import com.meshovik.transfer.FileTransferManager
import com.meshovik.transfer.FileTransferState
import com.meshovik.transfer.FileTransferStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Main ViewModel for the mesh messenger.
 * Coordinates BLE operations, file transfers, and UI state.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MeshViewModel(
    private val bleManager: BleManager,
    private val meshRepository: MeshRepository,
    private val fileTransferManager: FileTransferManager,
    applicationContext: Context
) : ScreenModel {
    private val scope = screenModelScope

    private val context = applicationContext.applicationContext
    // Local device address for message filtering
    private val localDeviceAddress: String = bleManager.getLocalAddress()
    private val bleToP2pMac = mutableMapOf<String, String>()
    // UI State
    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()
    private val processingAttachments = mutableSetOf<String>()
    // Events
    private val _events = MutableSharedFlow<MeshEvent>()
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()
    private var observersLaunched = false
    // Deduplicate received messages (must be declared BEFORE init)
    private val processedMessageIds = mutableSetOf<String>()

    init {
        Timber.e("=== MeshViewModel CREATED === instance=${System.identityHashCode(this)} | thread=${Thread.currentThread().name}")
        if (!observersLaunched) {
            observersLaunched = true

            observeBleState()
            observeDevices()
            observeMessages()
            observeFileTransfers()
            observeIncomingTransfers()
            observeWifiDirectPeers()
            observeControlMessages()

            _uiState.update { it.copy(localDeviceAddress = localDeviceAddress) }

            Timber.i("Observers launched for this ViewModel instance")
        } else {
            Timber.w("Observers already launched — skipping duplicate subscription")
        }
    }
    private fun observeControlMessages() {
        scope.launch {
            bleManager.controlMessages.collect { controlMsg ->
                if (controlMsg.type == ControlMessageType.READY_FOR_TRANSFER) {
                    Timber.i("Received READY_FOR_TRANSFER for ${controlMsg.attachmentId}")
                    _events.emit(
                        MeshEvent.ReadyForTransfer(
                            attachmentId = controlMsg.attachmentId,
                            senderAddress = controlMsg.senderId
                        )
                    )
                }
            }
        }
    }
    /**
     * Observes BLE manager state changes.
     */
    private fun observeBleState() {
       scope.launch {
           Timber.i(">>> LAUNCH observeBleState isScanning | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
           bleManager.isScanning.collect { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }

        scope.launch {
            Timber.i(">>> LAUNCH observeBleState isAdvertising | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
            bleManager.isAdvertising.collect { isAdvertising ->
                _uiState.update { it.copy(isAdvertising = isAdvertising) }
            }
        }

        scope.launch {
            Timber.i(">>> LAUNCH observeBleState connectionStates | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
            bleManager.connectionStates.collect { states ->
                _uiState.update { it.copy(connectionStates = states) }
            }
        }
    }

    /**
     * Observes discovered devices from BLE manager and syncs to repository.
     */
    private fun observeDevices() {
        scope.launch {
            Timber.i(">>> LAUNCH observeDevices | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
            bleManager.discoveredDevices.collect { devices ->
                // Deduplicate by address (safety net against BLE scanner emitting duplicates)
                val uniqueDevices = devices.distinctBy { it.address }
                uniqueDevices.forEach { device ->
                    meshRepository.updateDevice(device)
                    meshRepository.updateChatFromDevice(device)
                }
                _uiState.update { it.copy(devices = uniqueDevices) }
            }
        }
    }

    /**
     * Observes received messages from BLE manager.
     * При получении сообщения с вложением — автоматически запускает приём файла.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun observeMessages() {
        scope.launch {
            Timber.i(">>> LAUNCH observeMessages")
            bleManager.receivedMessages.collect { message ->
                if (message.id in processedMessageIds) {
                    Timber.d("Already processed: ${message.id}")
                    return@collect
                }

                processedMessageIds.add(message.id)

                val isOwnMessage = message.senderId == localDeviceAddress ||
                        message.senderId == bleManager.getLocalAddress()

                if (isOwnMessage) {
                    meshRepository.addSentMessage(message)
                    _uiState.update { it.copy(sentMessages = it.sentMessages + message) }
                    Timber.d("Own message echo: ${message.id}")
                    return@collect  // ← не обрабатываем дальше
                }

                // === Входящее сообщение ===
                meshRepository.addReceivedMessage(message)

                when {
                    // 1. Получили метаданные изображения
                    message.attachment != null -> {
                        val attachment = message.attachment
                        if (attachment.id in processingAttachments) {
                            Timber.w("Duplicate attachment skipped: ${attachment.id}")
                            return@collect
                        }

                        processingAttachments.add(attachment.id)

                        fileTransferManager.notifyIncomingTransfer(attachment)

                        launch {
                            try {
                                handleIncomingAttachment(message.senderId, attachment)
                            } finally {
                                processingAttachments.remove(attachment.id)
                            }
                        }
                    }
                }

                _uiState.update { it.copy(receivedMessages = it.receivedMessages + message) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun handleIncomingAttachment(senderAddress: String, attachment: Attachment) {
        try {
            Timber.i("Receiver: preparing group for ${attachment.id} $senderAddress")

            val groupReady = fileTransferManager.wifiDirectManager.ensureGroupAsOwner()
            if (!groupReady) {
                throw Exception("Не удалось создать Wi-Fi Direct группу")
            }

            bleManager.sendReadyForTransfer(
                targetAddress = senderAddress,
                attachmentId = attachment.id
            )

            Timber.i("Receiver: sent READY, starting file receive")
            val localUri = fileTransferManager.receiveFile(attachment.id, attachment)

            _events.emit(MeshEvent.FileReceived(attachment, localUri))

        } catch (e: Exception) {
            Timber.e(e, "handleIncomingAttachment failed for ${attachment.id}")
            _events.emit(MeshEvent.Error("Не удалось подготовить приём файла: ${e.message}"))
        }
    }
    /**
     * Observes file transfer states.
     */
    private fun observeFileTransfers() {
        scope.launch {
            Timber.i(">>> LAUNCH observeFileTransfers | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
            fileTransferManager.transfers.collect { transfers ->
                _uiState.update { it.copy(fileTransfers = transfers) }

                // Обновляем сообщения с завершёнными передачами
                transfers.values
                    .filter { it.status == FileTransferStatus.COMPLETED && !it.isSender }
                    .forEach { transferState ->
                        transferState.localUri?.let { uri ->
                            meshRepository.updateAttachmentLocalUri(transferState.transferId, uri)
                        }
                    }
            }
        }
    }

    /**
     * Observes incoming transfer requests (для показа "Получаем изображение...").
     */
    private fun observeIncomingTransfers() {
        scope.launch {
            Timber.i(">>> LAUNCH observeIncomingTransfers | thread=${Thread.currentThread().name} | active jobs=   $${scope.coroutineContext[Job]?.children?.count()}")
            fileTransferManager.incomingTransferRequests.collect { attachment ->
                Timber.i("Incoming transfer request: ${attachment.id}")
                _events.emit(MeshEvent.IncomingFileTransfer(attachment))
            }
        }
    }

    /**
     * Observes Wi-Fi Direct peers for debugging.
     */
    private fun observeWifiDirectPeers() {
        scope.launch {
            Timber.i(">>> LAUNCH observeWifiDirectPeers | thread=${Thread.currentThread().name} | active jobs=${scope.coroutineContext[Job]?.children?.count()}")
            fileTransferManager.wifiDirectManager.peers.collect { peers ->
                _uiState.update { it.copy(wifiDirectPeers = peers) }

                // Пытаемся сопоставить с известными BLE устройствами
                peers.forEach { p2pDevice ->
                    Timber.d("Wi-Fi Direct peer name=${p2pDevice.deviceName} addr=${p2pDevice.deviceAddress} status=${p2pDevice.status}")
                    val bleDevice = bleManager.discoveredDevices.value[0]

                    if (bleDevice != null) {
                        bleToP2pMac[bleDevice.address] = p2pDevice.deviceAddress
                        Timber.i("Wi-Fi Direct Mapped BLE ${bleDevice.address} → P2P ${p2pDevice.deviceAddress}")
                    }
                }
            }
        }
    }

    /**
     * Starts the mesh service (advertising + GATT server).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun startMeshService() {
        scope.launch {
            bleManager.startMeshService().collect { success ->
                if (success) {
                    Timber.i("Mesh service started successfully")
                    _events.emit(MeshEvent.ServiceStarted)
                } else {
                    Timber.e("Failed to start mesh service")
                    _events.emit(MeshEvent.Error("Failed to start mesh service"))
                }
            }
        }
    }

    /**
     * Stops the mesh service.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopMeshService() {
        bleManager.stopMeshService()
    }

    /**
     * Starts scanning for nearby devices.
     */
    fun startScanning() {
        bleManager.startScanning()
    }

    /**
     * Stops scanning.
     */
    fun stopScanning() {
        bleManager.stopScanning()
    }

    /**
     * Sends a text message to a specific device.
     */
    fun sendMessage(targetAddress: String, content: String) {
        if (content.isBlank()) return

        val message = bleManager.sendMessage(targetAddress, content)
        meshRepository.addSentMessage(message)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + message)
        }

        scope.launch {
            _events.emit(MeshEvent.MessageSent(message))
        }
    }

    /**
     * Broadcasts a message to all discovered devices.
     */
    fun broadcastMessage(content: String) {
        if (content.isBlank()) return

        val message = bleManager.broadcastMessage(content)
        meshRepository.addSentMessage(message)
        _uiState.update { state ->
            state.copy(sentMessages = state.sentMessages + message)
        }

        scope.launch {
            _events.emit(MeshEvent.MessageBroadcast(message))
        }
    }

    /**
     * Отправляет изображение:
     * 1. Создаёт Attachment с метаданными + thumbnail
     * 2. Отправляет метаданные по BLE
     * 3. Запускает передачу файла через Wi-Fi Direct
     *
     * @param targetAddress  MeshID или BLE-адрес получателя
     * @param imageUri       URI выбранного изображения
     * @param caption        Подпись (опционально)
     */
    fun sendImage(targetAddress: String, imageUri: Uri, caption: String = "") {
        scope.launch {
            val targetP2pMac = bleToP2pMac[targetAddress] ?: targetAddress

            // Получаем метаданные один раз
            val (fileName, mimeType, sizeBytes) = getFileMetadata(imageUri)
            val (width, height) = getImageDimensions(imageUri)

            var lastException: Exception? = null

            repeat(3) { attempt ->
                val attemptNumber = attempt + 1
                val attachmentId = UUID.randomUUID().toString().take(12)

                try {
                    Timber.i("Wi-Fi Direct Попытка отправки изображения $attemptNumber/3 | attachmentId=$attachmentId $targetP2pMac")

                    val attachment = Attachment(
                        id = attachmentId,
                        type = AttachmentType.IMAGE,
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        localUri = imageUri.toString(),
                        width = width,
                        height = height
                    )

                    // 1. Отправляем метаданные по BLE
                    val message = bleManager.sendMessageWithAttachment(
                        targetAddress = targetAddress,
                        attachment = attachment,
                        caption = caption
                    )

                    meshRepository.addSentMessage(message)
                    _uiState.update { it.copy(sentMessages = it.sentMessages + message) }
                    _events.emit(MeshEvent.MessageSent(message))

                    Timber.i("Wi-Fi Direct Ждём READY от получателя (попытка $attemptNumber)...")

                    // 2. Ждём подтверждения готовности группы
                    val readyEvent = withTimeoutOrNull(35_000) {
                        _events.filter { event ->
                            event is MeshEvent.ReadyForTransfer && event.attachmentId == attachmentId
                        }.first()
                    }

                    if (readyEvent == null) {
                        throw Exception("Wi-Fi Direct Получатель не ответил READY за 35 сек (попытка $attemptNumber)")
                    }

                    Timber.i("Wi-Fi Direct Получен READY → начинаем передачу файла (попытка $attemptNumber)")

                    // 3. Передаём файл
                    fileTransferManager.sendFile(
                        attachment = attachment,
                        localUri = imageUri.toString(),
                        targetMeshId = targetP2pMac
                    )

                    Timber.i("Wi-Fi Direct  Изображение успешно отправлено после $attemptNumber попытки")
                    return@launch // успех — выходим

                } catch (e: Exception) {
                    lastException = e
                    Timber.w(e, "Wi-Fi Direct Попытка $attemptNumber провалилась")

                    if (attempt < 2) {
                        delay(10_000L * attemptNumber)
                    }
                }
            }

            // Все попытки провалились
            Timber.e(lastException, "Wi-Fi Direct Не удалось отправить изображение после 3 попыток")
            _events.emit(MeshEvent.Error("Wi-Fi Direct Не удалось отправить изображение после 3 попыток: ${lastException?.message}"))
        }
    }

    fun cancelFileTransfer(transferId: String) {
        fileTransferManager.cancelTransfer(transferId)
    }

    /**
     * Повторить отправку файла при ошибке.
     */
    fun retryFileTransfer(transferId: String, targetAddress: String) {
        scope.launch {
            try {
                val transferState = fileTransferManager.getTransferState(transferId)
                if (transferState?.status == FileTransferStatus.FAILED) {
                    // Находим сообщение с этим attachmentId
                    val message = _uiState.value.sentMessages.find { it.attachment?.id == transferId }
                        ?: _uiState.value.receivedMessages.find { it.attachment?.id == transferId }

                    if (message?.attachment != null) {
                        Timber.i("Retrying file transfer: $transferId")
                        fileTransferManager.sendFile(
                            attachment = message.attachment,
                            localUri = message.attachment.localUri ?: transferState.localUri ?: "",
                            targetMeshId = targetAddress
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "retryFileTransfer failed: $transferId")
                _events.emit(MeshEvent.Error("Повтор отправки не удался: ${e.message}"))
            }
        }
    }

    fun getMessagesFlowForChat(chatId: String): StateFlow<List<MeshMessage>> {
        val resolvedChatId = resolveChatId(chatId)
        return meshRepository.getMessagesFlowForChat(resolvedChatId, localDeviceAddress)
    }

    /**
     * Gets messages for a specific chat.
     */
    fun getMessagesForChat(chatId: String): List<MeshMessage> {
        val resolvedChatId = resolveChatId(chatId)
        return meshRepository.getMessagesForChat(resolvedChatId, localDeviceAddress)
    }

    /**
     * Resolves a chat ID: if it's a BLE MAC address, tries to find the corresponding Mesh ID.
     * Returns the original chatId if no mapping found.
     */
    private fun resolveChatId(chatId: String): String {
        // Mesh IDs start with "Mesh", BLE MACs contain colons
        return if (!chatId.startsWith("Mesh") && chatId.contains(":")) {
            bleManager.getMeshIdByBleAddress(chatId) ?: chatId
        } else {
            chatId
        }
    }

    /**
     * Подключается к выбранному устройству
     */
    fun connectToDevice(device: MeshDevice) {
        scope.launch {
            val bleDevice = bleManager.connectToDevice(device.address)

            if (bleDevice != null) {
                _uiState.update { it.copy(selectedDevice = device) }
            } else {
                _events.emit(MeshEvent.Error("Не удалось подключиться к ${device.name}"))
            }
        }
    }

    /**
     * Gets the local device info.
     */
    fun getLocalDeviceInfo(): Pair<String, String> {
        return bleManager.getLocalAddress() to bleManager.getLocalName()
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Запускает приём файла по Wi-Fi Direct.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startReceivingFile(attachment: Attachment) {
        scope.launch {
            try {
                fileTransferManager.wifiDirectManager.createGroup()
                val current = fileTransferManager.getTransferState(attachment.id)
                if (current?.status == FileTransferStatus.TRANSFERRING ||
                    current?.status == FileTransferStatus.COMPLETED) {
                    Timber.i("Wi-Fi Direct File already in progress/completed, skipping")
                }

                Timber.i("Starting file receive: ${attachment.id}")
                val localUri = fileTransferManager.receiveFile(
                    transferId = attachment.id,
                    attachment = attachment
                )
                Timber.i("File received: ${attachment.id} -> $localUri")
                _events.emit(MeshEvent.FileReceived(attachment, localUri))
            } catch (e: Exception) {
                Timber.e(e, "Failed to receive file: ${attachment.id}")
                _events.emit(MeshEvent.Error("Не удалось получить файл: ${e.message}"))
            }
        }
    }

    /**
     * Получает метаданные файла по URI.
     */
    private fun getFileMetadata(uri: Uri): Triple<String, String, Long> {
        val contentResolver = context.contentResolver
        var fileName = "image_${System.currentTimeMillis()}.jpg"
        var mimeType = "image/jpeg"
        var sizeBytes = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
            }
        }

        contentResolver.getType(uri)?.let { mimeType = it }

        // Если размер не получен через cursor — читаем поток
        if (sizeBytes == 0L) {
            contentResolver.openInputStream(uri)?.use { stream ->
                sizeBytes = stream.available().toLong()
            }
        }

        return Triple(fileName, mimeType, sizeBytes)
    }

    /**
     * Генерирует thumbnail изображения в Base64 (100x100px, JPEG quality=60).
     */
    private fun generateThumbnail(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val thumbnailSize = 100
            val thumbnail = Bitmap.createScaledBitmap(originalBitmap, thumbnailSize, thumbnailSize, true)
            originalBitmap.recycle()

            val outputStream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            thumbnail.recycle()

            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "Failed to generate thumbnail")
            null
        }
    }

    /**
     * Получает размеры изображения.
     */
    private fun getImageDimensions(uri: Uri): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get image dimensions")
            Pair(0, 0)
        }
    }
}

/**
 * UI state for the mesh messenger.
 */
data class MeshUiState(
    val devices: List<MeshDevice> = emptyList(),
    val receivedMessages: List<MeshMessage> = emptyList(),
    val sentMessages: List<MeshMessage> = emptyList(),
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val connectionStates: Map<String, com.meshovik.ble.manager.BleManager.ConnectionState> = emptyMap(),
    val selectedDevice: MeshDevice? = null,
    val localDeviceAddress: String = "",
    /** Состояния всех активных/завершённых передач файлов */
    val fileTransfers: Map<String, FileTransferState> = emptyMap(),
    /** Список обнаруженных Wi-Fi Direct устройств (для отладки) */
    val wifiDirectPeers: List<android.net.wifi.p2p.WifiP2pDevice> = emptyList()
)

/**
 * UI events for the mesh messenger.
 */
sealed class MeshEvent {
    data object ServiceStarted : MeshEvent()
    data class MessageSent(val message: MeshMessage) : MeshEvent()
    data class MessageBroadcast(val message: MeshMessage) : MeshEvent()
    data class ReadyForTransfer(val attachmentId: String, val senderAddress: String) : MeshEvent()
    data class Error(val message: String) : MeshEvent()
    /** Получены метаданные входящего вложения по BLE */
    data class IncomingFileTransfer(val attachment: Attachment) : MeshEvent()
    /** Файл успешно получен по Wi-Fi Direct */
    data class FileReceived(val attachment: Attachment, val localUri: String) : MeshEvent()
}
