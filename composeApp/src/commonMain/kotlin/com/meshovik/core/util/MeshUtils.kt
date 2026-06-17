package com.meshovik.core.util

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.Instant as KotlinxInstant  // alias чтобы не было конфликтов

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
     * Formats kotlin.time.Instant to a human-readable time string (HH:mm:ss).
     */
    fun formatTimestamp(timestampMillis: Long): String {
        if (timestampMillis <= 0) return "--:--:--"

        val instant = Clock.System.now() // не нужно, просто конвертируем
        // Лучше использовать kotlinx.datetime для форматирования
        val kotlinxInstant = kotlinx.datetime.Instant.fromEpochMilliseconds(timestampMillis)
        val dateTime = kotlinxInstant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())

        return "${dateTime.hour.toString().padStart(2, '0')}:" +
                "${dateTime.minute.toString().padStart(2, '0')}"
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