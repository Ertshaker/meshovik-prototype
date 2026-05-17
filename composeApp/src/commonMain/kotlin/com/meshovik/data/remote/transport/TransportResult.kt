package com.meshovik.data.remote.transport

/**
 * Sealed class representing the result of a transport operation.
 * Provides a type-safe way to handle success, failure, and in-progress states.
 */
sealed class TransportResult<out T> {
    /**
     * Operation completed successfully with data.
     */
    data class Success<T>(val data: T) : TransportResult<T>()

    /**
     * Operation failed with an error.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val errorCode: Int? = null
    ) : TransportResult<Nothing>()

    /**
     * Operation is in progress (used for streaming/chunked transfers).
     */
    data class InProgress<T>(
        val data: T? = null,
        val progress: Float = 0f
    ) : TransportResult<T>()

    companion object {
        fun <T> success(data: T): TransportResult<T> = Success(data)
        fun error(message: String, cause: Throwable? = null, errorCode: Int? = null): TransportResult<Nothing> =
            Error(message, cause, errorCode)
        fun <T> inProgress(data: T? = null, progress: Float = 0f): TransportResult<T> =
            InProgress(data, progress)
    }
}

/**
 * Represents the connection state of a transport.
 */
sealed class TransportConnectionState {
    object Disconnected : TransportConnectionState()
    object Connecting : TransportConnectionState()
    data class Connected(val peerId: String) : TransportConnectionState()
    data class Error(val message: String) : TransportConnectionState()
}
