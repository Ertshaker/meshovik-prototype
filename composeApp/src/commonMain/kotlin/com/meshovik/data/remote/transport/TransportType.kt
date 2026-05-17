package com.meshovik.data.remote.transport

/**
 * Enum representing different transport types available in the mesh network.
 * Each transport has different characteristics like max payload size,
 * latency, and reliability.
 */
enum class TransportType(
    val maxPayloadSize: Int,
    val priority: Int
) {
    /**
     * Bluetooth Low Energy transport.
     * Low power, moderate speed, short range.
     * Max payload is limited by BLE MTU (typically ~480 bytes after headers).
     */
    BLE(
        maxPayloadSize = 480,
        priority = 1
    ),

    /**
     * Wi-Fi Direct transport.
     * Higher speed, moderate range, higher power consumption.
     * Can handle much larger payloads.
     */
    WIFI_DIRECT(
        maxPayloadSize = 8192,
        priority = 2
    ),

    /**
     * Internet transport (via cloud relay or direct TCP/UDP).
     * Global reach, depends on network quality.
     */
    INTERNET(
        maxPayloadSize = 65536,
        priority = 3
    );

    companion object {
        /**
         * Returns the best transport for a given message size.
         * Chooses the transport with highest priority that can handle the size.
         */
        fun selectForSize(size: Int, availableTransports: Set<TransportType>): TransportType? {
            return availableTransports
                .filter { it.maxPayloadSize >= size }
                .maxByOrNull { it.priority }
        }
    }
}
