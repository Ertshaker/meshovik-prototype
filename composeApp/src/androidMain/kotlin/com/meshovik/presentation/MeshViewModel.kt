package com.meshovik.presentation

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshovik.ble.manager.BleManager
import com.meshovik.data.repository.MeshRepository
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MeshMessageStatus
import com.meshovik.transfer.FileTransferManager
import com.meshovik.transfer.FileTransferState
import com.meshovik.transfer.FileTransferStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Main ViewModel for the mesh messenger.
 * Coordinates BLE operations, file transfers, and UI state.
 */
class MeshViewModel(
    private val bleManager: BleManager,
    private val meshRepository: MeshRepository,
    private val fileTransferManager: FileTransferManager,
    private val context: Context
) : ViewModel() {

    // Local device address for message filtering
    private val localDeviceAddress: String = bleManager.getLocalAddress()

    // UI State
    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<MeshEvent>()
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    // Deduplicate received messages (must be declared BEFORE init)
    private val processedMessageIds = mutableSetOf<String>()

    init {
        observeBleState()
        observeDevices()
        observeMessages()
        observeFileTransfers()
        observeIncomingTransfers()
        // Initialize local device address in UI state
        _uiState.update { it.copy(localDeviceAddress = localDeviceAddress) }
    }

    /**
     * Observes BLE manager state changes.
     */
    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.isScanning.collect { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }

        viewModelScope.launch {
            bleManager.isAdvertising.collect { isAdvertising ->
                _uiState.update { it.copy(isAdvertising = isAdvertising) }
            }
        }

        viewModelScope.launch {
            bleManager.connectionStates.collect { states ->
                _uiState.update { it.copy(connectionStates = states) }
            }
        }
    }

    /**
     * Observes discovered devices from BLE manager and syncs to repository.
     */
    private fun observeDevices() {
        viewModelScope.launch {
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
    private fun observeMessages() {
        viewModelScope.launch {
            bleManager.receivedMessages.collect { messages ->
                // Добавляем только те, которых ещё нет
                messages.forEach { message ->
                    if (message.id !in processedMessageIds) {
                        processedMessageIds.add(message.id)
                        meshRepository.addReceivedMessage(message)

                        // Если это сообщение с вложением — уведомляем FileTransferManager
                        message.attachment?.let { attachment ->
                            Timber.i("Received attachment metadata via BLE: ${attachment.id} (${attachment.fileName})")
                            fileTransferManager.notifyIncomingTransfer(attachment)
                            // Автоматически начинаем приём файла
                            startReceivingFile(attachment)
                        }
                    }
                }
                _uiState.update { it.copy(receivedMessages = messages) }
            }
        }
    }

    /**
     * Observes file transfer states.
     */
    private fun observeFileTransfers() {
        viewModelScope.launch {
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
        viewModelScope.launch {
            fileTransferManager.incomingTransferRequests.collect { attachment ->
                Timber.i("Incoming transfer request: ${attachment.id}")
                _events.emit(MeshEvent.IncomingFileTransfer(attachment))
            }
        }
    }

    /**
     * Starts the mesh service (advertising + GATT server).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun startMeshService() {
        viewModelScope.launch {
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

        viewModelScope.launch {
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

        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                Timber.i("sendImage: target=$targetAddress, uri=$imageUri")

                // Получаем метаданные файла
                val (fileName, mimeType, sizeBytes) = getFileMetadata(imageUri)
                val attachmentId = UUID.randomUUID().toString().take(12)

                // Генерируем thumbnail (Base64, ~100x100px)
                val thumbnailBase64 = generateThumbnail(imageUri)

                // Получаем размеры изображения
                val (width, height) = getImageDimensions(imageUri)

                val attachment = Attachment(
                    id = attachmentId,
                    type = AttachmentType.IMAGE,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    localUri = imageUri.toString(),
                    thumbnailBase64 = thumbnailBase64,
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

                _uiState.update { state ->
                    state.copy(sentMessages = state.sentMessages + message)
                }

                _events.emit(MeshEvent.MessageSent(message))

                // 2. Запускаем передачу файла через Wi-Fi Direct
                Timber.i("Starting Wi-Fi Direct file transfer: $attachmentId")
                fileTransferManager.sendFile(
                    attachment = attachment,
                    localUri = imageUri.toString(),
                    targetMeshId = targetAddress
                )

            } catch (e: Exception) {
                Timber.e(e, "sendImage failed")
                _events.emit(MeshEvent.Error("Не удалось отправить изображение: ${e.message}"))
            }
        }
    }

    /**
     * Отменить передачу файла.
     */
    fun cancelFileTransfer(transferId: String) {
        fileTransferManager.cancelTransfer(transferId)
    }

    fun getMessagesFlowForChat(chatId: String): StateFlow<List<MeshMessage>> {
        // If chatId looks like a BLE MAC address (not a Mesh ID), try to resolve it to a Mesh ID
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
        viewModelScope.launch {
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
    private fun startReceivingFile(attachment: Attachment) {
        viewModelScope.launch {
            try {
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onCleared() {
        super.onCleared()
        bleManager.cleanup()
        fileTransferManager.cleanup()
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
    val fileTransfers: Map<String, FileTransferState> = emptyMap()
)

/**
 * UI events for the mesh messenger.
 */
sealed class MeshEvent {
    data object ServiceStarted : MeshEvent()
    data class MessageSent(val message: MeshMessage) : MeshEvent()
    data class MessageBroadcast(val message: MeshMessage) : MeshEvent()
    data class Error(val message: String) : MeshEvent()
    /** Получены метаданные входящего вложения по BLE */
    data class IncomingFileTransfer(val attachment: Attachment) : MeshEvent()
    /** Файл успешно получен по Wi-Fi Direct */
    data class FileReceived(val attachment: Attachment, val localUri: String) : MeshEvent()
}
