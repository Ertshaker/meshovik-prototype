package com.meshovik.core.util

/**
 * Platform-agnostic logging interface.
 * Implementations use platform-specific logging frameworks (Timber on Android, NSLog on iOS).
 */
expect object Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
