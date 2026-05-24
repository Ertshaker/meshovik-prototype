package com.meshovik.core.util

import timber.log.Timber

/**
 * Android implementation of Logger using Timber.
 */
actual object Logger {
    actual fun d(tag: String, message: String) {
        Timber.d("[%s] %s", tag, message)
    }

    actual fun i(tag: String, message: String) {
        Timber.i("[%s] %s", tag, message)
    }

    actual fun w(tag: String, message: String) {
        Timber.w("[%s] %s", tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Timber.e(throwable, "[%s] %s", tag, message)
        } else {
            Timber.e("[%s] %s", tag, message)
        }
    }
}
