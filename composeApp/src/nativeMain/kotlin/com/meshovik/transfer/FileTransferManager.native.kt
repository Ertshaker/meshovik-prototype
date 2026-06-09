package com.meshovik.transfer

import com.meshovik.domain.entity.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Native (iOS) заглушка FileTransferManager.
 * Wi-Fi Direct недоступен на iOS — будущая реализация через Multipeer Connectivity.
 */
actual class FileTransferManager {

    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    actual val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val _incomingTransferRequests = MutableSharedFlow<Attachment>()
    actual val incomingTransferRequests: Flow<Attachment> = _incomingTransferRequests

    actual suspend fun sendFile(
        attachment: Attachment,
        localUri: String,
        targetMeshId: String
    ): String {
        throw NotImplementedError("File transfer not implemented on iOS/Native yet")
    }

    actual suspend fun receiveFile(
        transferId: String,
        attachment: Attachment
    ): String {
        throw NotImplementedError("File transfer not implemented on iOS/Native yet")
    }

    actual fun cancelTransfer(transferId: String) {
        // no-op
    }

    actual fun getTransferState(transferId: String): FileTransferState? = null

    actual fun cleanup() {
        // no-op
    }
}
