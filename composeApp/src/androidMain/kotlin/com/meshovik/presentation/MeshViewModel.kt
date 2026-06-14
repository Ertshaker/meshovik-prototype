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
import kotlinx.coroutines.runBlocking
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
            observeControlMessages()

            _uiState.update { it.copy(localDeviceAddress = localDeviceAddress) }

            Timber.i("Observers launched for this ViewModel instance")
        } else {
            Timber.w("Observers already launched — skipping duplicate subscription")
        }

        bleManager.disconnectFromDevice("skibid")
        bleManager.stopMeshService()
        bleManager.stopGattServer()
        bleManager.stopScanning()
        bleManager.stopAdvertising()

        fileTransferManager.Advertising()
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
            bleManager.receivedMessages.collect { message ->
                if (message.id in processedMessageIds) return@collect
                processedMessageIds.add(message.id)

                val isOwnMessage = message.senderId == localDeviceAddress ||
                        message.senderId == bleManager.getLocalAddress()

                if (isOwnMessage) {
                    meshRepository.addSentMessage(message)
                    _uiState.update { it.copy(sentMessages = it.sentMessages + message) }
                    Timber.d("Own message echo: ${message.id}")
                    return@collect
                }

                meshRepository.addReceivedMessage(message)

                message.attachment?.let { attachment ->
                    if (attachment.id in processingAttachments) return@let

                    processingAttachments.add(attachment.id)

                    launch {
                        try {
                            handleIncomingAttachment(message.senderId, attachment)
                        } finally {
                            processingAttachments.remove(attachment.id)
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
            Timber.i("Receiver: starting receive for ${attachment.id}")

            val localUri = fileTransferManager.receiveFile(attachment.id, attachment, senderAddress)
            _events.emit(MeshEvent.FileReceived(attachment, localUri))
        } catch (e: Exception) {
            Timber.e(e, "handleIncomingAttachment failed")
            _events.emit(MeshEvent.Error("Не удалось получить файл"))
        }
    }
    /**
     * Observes file transfer states.
     */
    private fun observeFileTransfers() {
        scope.launch {
            fileTransferManager.transfers.collect { transfers ->
                _uiState.update { it.copy(fileTransfers = transfers) }

                transfers.values
                    .filter { it.status == FileTransferStatus.COMPLETED && !it.isSender }
                    .forEach { state ->
                        state.localUri?.let { uri ->
                            meshRepository.updateAttachmentLocalUri(state.transferId, uri)
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
            fileTransferManager.incomingTransferRequests.collect { attachment ->
                _events.emit(MeshEvent.IncomingFileTransfer(attachment))
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
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    fun sendImage(targetAddress: String, imageUri: Uri, caption: String = "") {
        scope.launch{
            try {
                val (fileName, mimeType, sizeBytes) = getFileMetadata(imageUri)
                val (width, height) = getImageDimensions(imageUri)
                val attachmentId = UUID.randomUUID().toString().take(12)

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

                val message = bleManager.sendMessageWithAttachment(targetAddress, attachment, caption)
delay(3000)
                meshRepository.addSentMessage(message)
                _uiState.update { it.copy(sentMessages = it.sentMessages + message) }
                _events.emit(MeshEvent.MessageSent(message))
                Timber.i("Nearby Отправляю изображение $targetAddress")

                // Отправляем файл через Nearby
                fileTransferManager.sendFile(
                    attachment = attachment,
                    localUri = imageUri.toString(),
                    targetMeshId = targetAddress
                )


            } catch (e: Exception) {
                Timber.e(e, "sendImage failed")
                _events.emit(MeshEvent.Error("Не удалось отправить изображение: ${e.message}"))
            } finally {
            }
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
            val transferState = fileTransferManager.getTransferState(transferId)
            if (transferState?.status == FileTransferStatus.FAILED) {
                val message = _uiState.value.sentMessages.find { it.attachment?.id == transferId }
                    ?: _uiState.value.receivedMessages.find { it.attachment?.id == transferId }

                message?.attachment?.let {
                    fileTransferManager.sendFile(it, it.localUri ?: "", targetAddress)
                }
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
