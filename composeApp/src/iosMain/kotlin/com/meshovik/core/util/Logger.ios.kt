package com.meshovik.core.util

import platform.Foundation.NSLog

/**
 * iOS implementation of Logger using NSLog.
 */
actual object Logger {
    actual fun d(tag: String, message: String) {
        NSLog("[DEBUG] [$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        NSLog("[INFO] [$tag] $message")
    }

    actual fun w(tag: String, message: String) {
        NSLog("[WARN] [$tag] $message")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val errorMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        NSLog("[ERROR] [$tag] $errorMessage")
    }
}
