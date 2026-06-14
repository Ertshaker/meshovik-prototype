package com.meshovik.transfer

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.net.toUri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshovik.ble.manager.BleManager
import com.meshovik.domain.entity.Attachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
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

actual class FileTransferManager(private val context: Context, private val bleManager: BleManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = Nearby.getConnectionsClient(context)

    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    actual val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val _incomingTransferRequests = MutableSharedFlow<Attachment>(extraBufferCapacity = 8)
    actual val incomingTransferRequests: Flow<Attachment> = _incomingTransferRequests

    private val connectionStates = mutableMapOf<String, ConnectionState>()

    enum class ConnectionState {
        PENDING_OUTGOING,   // мы запросили
        PENDING_INCOMING,   // к нам запросили
        CONNECTED,
        REJECTED
    }

    // endpointId -> Connection info
    private val activeEndpoints = ConcurrentHashMap<String, String>() // endpointId -> transferId
    private val outputStreams = ConcurrentHashMap<String, FileOutputStream>() // transferId -> stream

    private val SERVICE_ID = "com.meshovik.mesh"
    init {
        Timber.i("Nearby Connections Manager initialized")

    }

    fun Advertising() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        startAdvertising("skibidi")
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    actual suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String
    ): String {
        val transferId = attachment.id
        Timber.i("Nearby sendFile → $transferId to $targetMeshId")
        bleManager.disconnectFromDevice(targetMeshId)
        bleManager.stopGattServer()
        bleManager.stopScanning()
        bleManager.stopAdvertising()

        updateTransfer(transferId, FileTransferStatus.PENDING, attachment.sizeBytes, true)
        Timber.i("Отключил всё дерьмо")

        try {
            startAdvertising(transferId)
            startDiscovery(targetMeshId, transferId)

            val uri = localUri.toUri()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Не удалось открыть файл")

            val payload = Payload.fromStream(inputStream)
            activeEndpoints.forEach { (endpointId, _) ->
                client.sendPayload(endpointId, payload)
                Timber.i("Sent payload to $endpointId")
            }

            updateTransfer(transferId, FileTransferStatus.COMPLETED, attachment.sizeBytes, true, localUri = localUri)
            return transferId
        } catch (e: Exception) {
            Timber.e(e, "sendFile failed")
            updateTransfer(transferId, FileTransferStatus.FAILED, attachment.sizeBytes, true, e.message)
            throw e
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    actual suspend fun receiveFile(
        transferId: String,
        attachment: Attachment,
        senderAddress: String
    ): String {
        Timber.i("Nearby receiveFile started: $transferId")
        bleManager.disconnectFromDevice(senderAddress)
        bleManager.stopGattServer()
        bleManager.stopScanning()
        bleManager.stopAdvertising()

        updateTransfer(
            transferId = transferId,
            status = FileTransferStatus.TRANSFERRING,
            totalBytes = attachment.sizeBytes,
            isSender = false
        )


        Timber.i("Отключил всё дерьмо")
        // Запускаем advertising, чтобы sender мог подключиться
        startAdvertising(senderAddress)

        return withContext(Dispatchers.IO) {
            val outputFile = createOutputFile(attachment)
            val outputStream = FileOutputStream(outputFile)
            outputStreams[transferId] = outputStream

            // Ждём завершения передачи через SharedFlow / callback
            val completionDeferred = CompletableDeferred<String>()

            // Временный listener для этого transferId
            val job = scope.launch {
                // Здесь можно использовать отдельный flow, но для простоты используем delay + проверку
                // В реальной версии лучше использовать channel или SharedFlow
                try {
                    withTimeout(90_000) { // 1.5 минуты таймаут
                        while (isActive) {
                            val state = _transfers.value[transferId]
                            if (state?.status == FileTransferStatus.COMPLETED) {
                                completionDeferred.complete(state.localUri ?: outputFile.toUri().toString())
                                return@withTimeout
                            }
                            if (state?.status == FileTransferStatus.FAILED) {
                                completionDeferred.completeExceptionally(Exception(state.errorMessage))
                                return@withTimeout
                            }
                        }
                    }
                } catch (e: Exception) {
                    completionDeferred.completeExceptionally(e)
                }
            }

            try {
                val localUri = completionDeferred.await()
                Timber.i("Nearby receiveFile completed: $transferId → $localUri")
                localUri
            } catch (e: Exception) {
                Timber.e(e, "receiveFile failed")
                updateTransfer(transferId, FileTransferStatus.FAILED, attachment.sizeBytes, false, error = e.message)
                throw e
            } finally {
                job.cancel()
                outputStreams.remove(transferId)?.close()
            }
        }
    }

    actual fun cancelTransfer(transferId: String) {
        client.stopAllEndpoints()
        outputStreams.remove(transferId)?.close()
        updateTransfer(transferId, FileTransferStatus.CANCELLED)
    }

    actual fun getTransferState(transferId: String): FileTransferState? = _transfers.value[transferId]

    actual fun cleanup() {
        client.stopAllEndpoints()
        outputStreams.values.forEach { it.close() }
        outputStreams.clear()
        activeEndpoints.clear()
    }

    private fun startAdvertising(transferId: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .setConnectionType(ConnectionType.DISRUPTIVE)
            .build()

        client.startAdvertising(
            "Mesh_${transferId.take(1)}",
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener { Timber.i("Advertising started") }
            .addOnFailureListener { Timber.e(it, "Advertising failed") }
    }

    private fun startDiscovery(targetId: String, transferId: String) {
        connectionStates.clear()
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Timber.i("Discovery started") }
    }
    private fun acceptConnectionSafely(endpointId: String) {
        // Небольшая задержка часто помогает при race condition
        Handler(Looper.getMainLooper()).postDelayed({
            client.acceptConnection(endpointId, payloadCallback)
        }, 120) // 80–200 мс обычно достаточно
    }
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Timber.i("Connection initiated from $endpointId")
            val currentState = connectionStates[endpointId]

            // Если мы уже отправили запрос — просто принимаем
            if (currentState == ConnectionState.PENDING_OUTGOING) {
                Timber.d("Мы уже инициировали — принимаем")
                acceptConnectionSafely(endpointId)
                return
            }
            connectionStates[endpointId] = ConnectionState.PENDING_INCOMING
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Timber.i("✅ Соединение установлено: $endpointId")
                    connectionStates[endpointId] = ConnectionState.CONNECTED
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Timber.w("❌ Отклонено: $endpointId")
                    connectionStates[endpointId] = ConnectionState.REJECTED
                }
                else -> {
                    Timber.e("Ошибка соединения $endpointId: ${resolution.status}")
                    connectionStates.remove(endpointId) // можно retry позже
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("Disconnected from $endpointId")
            activeEndpoints.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.i("Found endpoint: $endpointId (${info.endpointName})")
            if (connectionStates.containsKey(endpointId)) {
                Timber.d("Уже есть соединение/запрос к $endpointId — пропускаем")
                return
            }

            connectionStates[endpointId] = ConnectionState.PENDING_OUTGOING
            client.stopDiscovery()
            client.requestConnection(info.endpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.i("Endpoint lost: $endpointId")
            connectionStates.remove(endpointId)
            bleManager.startMeshService()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val transferId = activeEndpoints[endpointId] ?: return

            if (payload.type == Payload.Type.FILE) {
                // TODO: обработка больших файлов
                Timber.i("Received file payload for $transferId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val transferId = activeEndpoints[endpointId] ?: return

            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Timber.i("Payload transfer SUCCESS for $transferId")

                    val outputStream = outputStreams.remove(transferId)
                    outputStream?.close()

                    val outputFile = createOutputFile(Attachment(
                        id = transferId,
                        type = com.meshovik.domain.entity.AttachmentType.IMAGE, // или FILE
                        fileName = "received_file",
                        mimeType = "application/octet-stream",
                        sizeBytes = update.totalBytes
                    ))

                    val localUri = outputFile.toUri().toString()
                    updateTransfer(transferId, FileTransferStatus.COMPLETED, update.totalBytes, false, localUri = localUri)
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val progress = if (update.totalBytes > 0)
                        update.bytesTransferred.toFloat() / update.totalBytes else 0f
                    updateTransferProgress(transferId, progress)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    Timber.e("Payload transfer FAILED for $transferId")
                    updateTransfer(transferId, FileTransferStatus.FAILED)
                }
            }
        }
    }

    private fun updateTransferProgress(transferId: String, progress: Float) {
        _transfers.update { current ->
            current + (transferId to (current[transferId]?.copy(
                status = FileTransferStatus.TRANSFERRING,
                progressBytes = progress.toLong()
            ) ?: FileTransferState(transferId, FileTransferStatus.TRANSFERRING)))
        }
    }



    private fun updateTransfer(
        transferId: String,
        status: FileTransferStatus,
        totalBytes: Long = 0,
        isSender: Boolean = false,
        localUri: String? = null,
        error: String? = null
    ) {
        _transfers.update { current ->
            current + (transferId to FileTransferState(
                transferId = transferId,
                status = status,
                totalBytes = totalBytes,
                isSender = isSender,
                localUri = localUri,
                errorMessage = error
            ))
        }
    }

    private fun createOutputFile(attachment: Attachment): File {
        val dir = File(context.getExternalFilesDir(null), "MeshTransfers").also { it.mkdirs() }
        return File(dir, "${attachment.id}_${attachment.fileName}")
    }
}



