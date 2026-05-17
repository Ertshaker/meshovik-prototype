package com.meshovik.data.remote.transport

import android.annotation.SuppressLint
import com.meshovik.ble.manager.BleManager
import com.meshovik.ble.model.BleConstants
import com.meshovik.core.util.MeshUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
     * Waits for a peer to be connected before sending.
     * Times out after 5 seconds if the peer is not discovered.
     */
    private suspend fun waitForConnection(peerId: String): TransportResult<Unit> {
        // Check if already connected
        val connectedNeighbors = bleManager.getConnectedNeighbors()
        if (connectedNeighbors.contains(peerId)) {
            return TransportResult.success(Unit)
        }

        Timber.d("Peer $peerId not connected, waiting for connection (timeout: 5s)")

        // Wait for the peer to appear in connected neighbors
        val result = withTimeoutOrNull(5000) {
            bleManager.connectedNeighborsCount.collectLatest { count ->
                if (count > 0) {
                    val neighbors = bleManager.getConnectedNeighbors()
                    if (neighbors.contains(peerId)) {
                        Timber.d("Peer $peerId is now connected")
                        return@collectLatest
                    }
                }
            }
            false
        }

        return if (result == true) {
            TransportResult.success(Unit)
        } else {
            Timber.w("Timeout waiting for connection to $peerId")
            TransportResult.error("Timeout waiting for connection to $peerId")
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
     * Observes events from the underlying BleManager and forwards them.
     * All collections run on Dispatchers.IO to prevent main thread blocking.
     */
    private var lastReceivedMessageCount = 0

    private fun observeBleEvents() {
        scope.launch {
            bleManager.receivedMessages.collectLatest { messages ->
                // Only process new messages, skip already processed ones
                for (i in lastReceivedMessageCount until messages.size) {
                    val message = messages[i]
                    val incoming = IncomingMessage(
                        senderId = message.senderId,
                        payload = message.content.toByteArray(Charsets.UTF_8),
                        transportType = TransportType.BLE
                    )
                    _incomingMessages.emit(incoming)
                    Timber.d("Incoming BLE message from ${message.senderId}")
                }
                lastReceivedMessageCount = messages.size
            }
        }

        scope.launch {
            bleManager.discoveredDevices.collectLatest { devices ->
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
