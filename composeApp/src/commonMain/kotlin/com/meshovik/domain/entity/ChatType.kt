package com.meshovik.domain.entity

/**
 * Type of chat conversation.
 */
enum class ChatType {
    /**
     * Broadcast chat - messages sent to all discovered devices.
     */
    BROADCAST,

    /**
     * Direct chat - private messages to a specific device.
     */
    DIRECT
}
