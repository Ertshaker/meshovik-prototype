package com.meshovik.data.remote.transport

import android.annotation.SuppressLint
import com.meshovik.ble.manager.BleManager
import com.meshovik.ble.model.BleConstants
import com.meshovik.core.util.MeshUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * BLE transport implementation that adapts the existing BleManager
 * to the MessageTransport interface.
 * All BLE operations run on Dispatchers.IO to prevent ANR.
 *
 * @param bleManager The DI-provided BleManager singleton to adapt.
 */
class BleTransport(
    private val bleManager: BleManager
) : MessageTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<TransportConnectionState>(extraBufferCapacity = 16)
    override val connectionState: SharedFlow<TransportConnectionState> = _connectionState.asSharedFlow()

    override val transportType: TransportType = TransportType.BLE
    override var isAvailable: Boolean = false
        private set

    private var isInitialized = false

    override suspend fun initialize(): TransportResult<Unit> {
        return try {
            if (isInitialized) {
                return TransportResult.success(Unit)
            }

            observeBleEvents()

            // Start the mesh service - use first() to get the initial result
            // The callbackFlow emits once and then waits for cancellation
            val success = bleManager.startMeshService().flowOn(Dispatchers.IO).first()
            isAvailable = success
            
            if (success) {
                isInitialized = true
                _connectionState.emit(TransportConnectionState.Connected(bleManager.getLocalAddress()))
                Timber.i("BLE transport initialized, mesh service started, isAvailable=$isAvailable")
            } else {
                _connectionState.emit(TransportConnectionState.Error("Failed to start mesh service"))
                Timber.e("BLE transport initialization failed")
            }

            TransportResult.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize BLE transport")
            TransportResult.error("Initialization failed: ${e.message}", e)
        }
    }

    override fun shutdown() {
        // Don't call cleanup() - the BleManager lifecycle is managed by DI
        isAvailable = false
        isInitialized = false
        Timber.i("BLE transport shut down")
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(peerId: String, payload: ByteArray): TransportResult<Unit> {
        return try {
            if (!isAvailable) {
                return TransportResult.error("BLE transport not available")
            }

            // Connection-aware sending: wait for peer to be discovered/connected
            waitForConnection(peerId)

            val packetId = MeshUtils.generatePacketId()
            val packetData = bleManager.createMeshPacket(
                packetId = packetId,
                ttl = BleConstants.DEFAULT_TTL,
                hopCount = 0,
                payload = payload
            )

            // Retry with backoff if send fails (handles transient GATT errors)
            val maxRetries = 2
            for (attempt in 0..maxRetries) {
                val success = bleManager.sendData(peerId, packetData)
                if (success) {
                    Timber.d("BLE send successful to $peerId (${payload.size} bytes, attempt ${attempt + 1})")
                    return TransportResult.success(Unit)
                }
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (attempt + 1)
                    Timber.w("BLE send failed to $peerId (attempt ${attempt + 1}), retrying in ${backoffMs}ms")
                    delay(backoffMs)
                }
            }
            TransportResult.error("Failed to send via BLE to $peerId after ${maxRetries + 1} attempts")
        } catch (e: Exception) {
            Timber.e(e, "BLE send failed to $peerId")
            TransportResult.error("Send failed: ${e.message}", e)
        }
    }

    /**
     * FIX #6: Waits for a peer to be connected using StateFlow<Set<String>>.
     * Uses filter + firstOrNull for reliable connection detection.
     * Times out after 8 seconds if the peer is not discovered (increased from 5s).
     */
    private suspend fun waitForConnection(peerId: String): TransportResult<Unit> {
        // Быстрая проверка
        if (bleManager.getConnectedNeighbors().contains(peerId)) {
            Timber.d("Peer $peerId already connected")
            return TransportResult.success(Unit)
        }

        Timber.d("Waiting for connection to $peerId (timeout: 12s)")

        return try {
            withTimeout(12000) {
                // Более правильный и эффективный способ
                bleManager.connectedNeighbors
                    .filter { it.contains(peerId) }
                    .first()   // первый элемент, который удовлетворяет условию

                Timber.d("Peer $peerId successfully connected")
                TransportResult.success(Unit)
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("Timeout waiting for connection to $peerId after 12s")
            TransportResult.error("Connection timeout for peer $peerId")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error while waiting for $peerId")
            TransportResult.error("Error waiting for connection: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun broadcast(payload: ByteArray): TransportResult<Unit> {
        return try {
            if (!isAvailable) {
                return TransportResult.error("BLE transport not available")
            }

            val packetId = MeshUtils.generatePacketId()
            val packetData = bleManager.createMeshPacket(
                packetId = packetId,
                ttl = BleConstants.DEFAULT_TTL,
                hopCount = 0,
                payload = payload
            )

            // Use the proper broadcastToAllNeighbors method - no toString() conversion
            bleManager.broadcastToAllNeighbors(packetData)
            Timber.d("BLE broadcast sent (${payload.size} bytes)")
            TransportResult.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BLE broadcast failed")
            TransportResult.error("Broadcast failed: ${e.message}", e)
        }
    }

    override suspend fun connect(peerId: String): TransportResult<Unit> {
        return try {
            if (!isAvailable) {
                return TransportResult.error("BLE transport not available")
            }

            // BLE mesh doesn't require explicit connection - flooding handles routing
            // But we can start scanning to discover the peer
            bleManager.startScanning()
            _connectionState.emit(TransportConnectionState.Connecting)
            Timber.d("BLE connect requested for $peerId (scanning started)")
            TransportResult.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BLE connect failed for $peerId")
            TransportResult.error("Connect failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect(peerId: String) {
        try {
            bleManager.stopScanning()
            _connectionState.emit(TransportConnectionState.Disconnected)
            Timber.d("BLE disconnect from $peerId")
        } catch (e: Exception) {
            Timber.e(e, "BLE disconnect failed")
        }
    }

    /**
     * FIX #7: Observes events from the underlying BleManager.
     * Uses SharedFlow for individual message events (no index tracking needed).
     */
    private fun observeBleEvents() {
        // FIX: Use receivedMessageEvents SharedFlow instead of index-based list tracking
        scope.launch {
            bleManager.receivedMessageEvents.collect { message ->
                val incoming = IncomingMessage(
                    senderId = message.senderId,
                    payload = message.content.toByteArray(Charsets.UTF_8),
                    transportType = TransportType.BLE
                )
                _incomingMessages.emit(incoming)
                Timber.d("Incoming BLE message from ${message.senderId}")
            }
        }

        scope.launch {
            bleManager.discoveredDevices.collect { devices ->
                for (device in devices) {
                    if (device.isOnline) {
                        _connectionState.emit(TransportConnectionState.Connected(device.address))
                    }
                }
            }
        }
    }

    /**
     * Returns the underlying BleManager for advanced operations.
     */
    fun getBleManager(): BleManager = bleManager
}
