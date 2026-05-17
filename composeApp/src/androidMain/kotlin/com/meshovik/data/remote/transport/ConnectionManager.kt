package com.meshovik.data.remote.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * ConnectionManager orchestrates multiple message transports.
 * It registers transports, selects the optimal one based on message size,
 * and provides a unified interface for sending/receiving messages.
 */
class ConnectionManager {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableMapOf<TransportType, MessageTransport>()
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private val _connectionStates = MutableSharedFlow<TransportConnectionState>(extraBufferCapacity = 16)
    val connectionStates: SharedFlow<TransportConnectionState> = _connectionStates.asSharedFlow()

    /**
     * Registers a transport with the manager.
     * The transport will be initialized and its incoming messages will be forwarded.
     */
    suspend fun registerTransport(transport: MessageTransport): TransportResult<Unit> {
        return mutex.withLock {
            if (transports.containsKey(transport.transportType)) {
                Timber.w("Transport ${transport.transportType} already registered, replacing")
                transports[transport.transportType]?.shutdown()
            }

            val initResult = transport.initialize()
            if (initResult is TransportResult.Error) {
                Timber.e("Failed to initialize transport ${transport.transportType}: ${initResult.message}")
                return@withLock initResult
            }

            transports[transport.transportType] = transport
            Timber.i("Transport ${transport.transportType} registered successfully")

            // Launch background coroutines to collect incoming messages
            scope.launch {
                transport.incomingMessages.collect { message ->
                    _incomingMessages.emit(message)
                    Timber.d("Message forwarded from ${transport.transportType}: ${message.senderId}")
                }
            }

            // Launch background coroutine to collect connection state changes
            scope.launch {
                transport.connectionState.collect { state ->
                    _connectionStates.emit(state)
                    Timber.d("Connection state from ${transport.transportType}: $state")
                }
            }

            TransportResult.success(Unit)
        }
    }

    /**
     * Unregisters a transport from the manager.
     */
    suspend fun unregisterTransport(type: TransportType) {
        mutex.withLock {
            transports.remove(type)?.shutdown()
            Timber.i("Transport $type unregistered")
        }
    }

    /**
     * Gets all registered transports.
     */
    fun getRegisteredTransports(): Set<TransportType> {
        return transports.keys.toSet()
    }

    /**
     * Gets a specific transport by type.
     */
    fun getTransport(type: TransportType): MessageTransport? {
        return transports[type]
    }

    /**
     * Selects the optimal transport for a given payload size.
     * Returns the transport with highest priority that can handle the size.
     */
    fun selectOptimalTransport(payloadSize: Int): MessageTransport? {
        val available = transports.values.filter { it.isAvailable }
        val availableTypes = available.map { it.transportType }.toSet()
        val bestType = TransportType.selectForSize(payloadSize, availableTypes)
        return bestType?.let { transports[it] }
    }

    /**
     * Sends data to a peer using the optimal transport.
     */
    suspend fun send(peerId: String, payload: ByteArray): TransportResult<Unit> {
        val transport = selectOptimalTransport(payload.size)
            ?: transports.values.firstOrNull { it.isAvailable }

        if (transport == null) {
            val error = "No available transport for sending to $peerId"
            Timber.e(error)
            return TransportResult.error(error)
        }

        Timber.d("Sending to $peerId via ${transport.transportType} (${payload.size} bytes)")
        return transport.send(peerId, payload)
    }

    /**
     * Broadcasts data to all peers using all available transports.
     */
    suspend fun broadcast(payload: ByteArray): TransportResult<Unit> {
        val availableTransports = transports.values.filter { it.isAvailable }
        if (availableTransports.isEmpty()) {
            val error = "No available transports for broadcasting"
            Timber.e(error)
            return TransportResult.error(error)
        }

        var lastResult: TransportResult<Unit> = TransportResult.success(Unit)
        for (transport in availableTransports) {
            val result = transport.broadcast(payload)
            if (result is TransportResult.Error) {
                Timber.w("Broadcast failed on ${transport.transportType}: ${result.message}")
                lastResult = result
            } else {
                Timber.d("Broadcast sent via ${transport.transportType}")
            }
        }
        return lastResult
    }

    /**
     * Connects to a peer using the specified transport type.
     */
    suspend fun connect(peerId: String, type: TransportType? = null): TransportResult<Unit> {
        val transport = type?.let { transports[it] }
            ?: transports.values.firstOrNull { it.isAvailable }

        if (transport == null) {
            return TransportResult.error("No available transport for connection")
        }

        return transport.connect(peerId)
    }

    /**
     * Disconnects from a peer.
     */
    suspend fun disconnect(peerId: String, type: TransportType? = null) {
        if (type != null) {
            transports[type]?.disconnect(peerId)
        } else {
            transports.values.forEach { it.disconnect(peerId) }
        }
    }

    /**
     * Shuts down all registered transports.
     */
    fun shutdown() {
        transports.values.forEach { it.shutdown() }
        transports.clear()
        Timber.i("All transports shut down")
    }
}
