package com.meshovik.core.util

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Utility functions for the mesh network.
 */
object MeshUtils {

    /**
     * Generates a unique packet ID.
     */
    fun generatePacketId(): String {
        return Clock.System.now().toEpochMilliseconds()
            .toString(16)
            .padStart(8, '0') +
                (0..3).map { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
    }

    /**
     * Formats kotlinx.datetime.Instant to a human-readable time string (HH:mm:ss).
     */
    fun formatTimestamp(instant: kotlin.time.Instant): String {
        if (instant == Instant.DISTANT_PAST) {
            return "--:--:--"
        }

//        return "${dateTime.hour.toString().padStart(2, '0')}:" +
//                "${dateTime.minute.toString().padStart(2, '0')}:" +
//                "${dateTime.second.toString().padStart(2, '0')}"

        return instant.toString()
    }

    /**
     * Formats a device address for display (last 4 characters).
     */
    fun formatAddress(address: String): String {
        return address.takeLast(4).uppercase()
    }

    /**
     * Checks if a device is considered online (seen within the last 30 seconds).
     */
    fun isDeviceOnline(lastSeen: Instant): Boolean {
        if (lastSeen == Instant.DISTANT_PAST) return false

        val now = Clock.System.now()
        val diff = now - lastSeen

        return diff < 30.seconds
    }

    /**
     * Returns how long ago the device was last seen (in seconds).
     */
    fun secondsSinceLastSeen(lastSeen: Instant): Long {
        if (lastSeen == Instant.DISTANT_PAST) return Long.MAX_VALUE

        val now = Clock.System.now()
        return (now - lastSeen).inWholeSeconds
    }
}