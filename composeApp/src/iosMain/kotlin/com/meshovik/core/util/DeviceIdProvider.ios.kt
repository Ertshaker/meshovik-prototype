package com.meshovik.core.util

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

/**
 * iOS implementation of DeviceIdProvider using NSUserDefaults.
 */
actual class DeviceIdProvider {
    private val defaults by lazy {
        NSUserDefaults.standardUserDefaults
    }

    /**
     * Returns a stable device ID, generating one if it doesn't exist.
     * Format: "Mesh" + 8 hex characters (e.g., "MeshA1B2C3D4")
     */
    actual fun getDeviceId(): String {
        return defaults.stringForKey(KEY_DEVICE_ID) ?: run {
            val newId = generateDeviceId()
            defaults.setObject(newId, KEY_DEVICE_ID)
            defaults.synchronize()
            newId
        }
    }

    /**
     * Returns the user's display name, or a default if not set.
     */
    actual fun getUserName(): String {
        return defaults.stringForKey(KEY_USER_NAME) ?: DEFAULT_USER_NAME
    }

    /**
     * Sets the user's display name.
     */
    actual fun setUserName(name: String) {
        defaults.setObject(name, KEY_USER_NAME)
        defaults.synchronize()
    }

    private fun generateDeviceId(): String {
        val uuid = NSUUID.UUID().UUIDString.replace("-", "")
        return "Mesh${uuid.take(8).uppercase()}"
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_NAME = "user_name"
        private const val DEFAULT_USER_NAME = "Meshovik User"
    }
}
