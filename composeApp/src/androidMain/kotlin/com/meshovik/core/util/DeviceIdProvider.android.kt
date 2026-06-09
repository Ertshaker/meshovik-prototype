package com.meshovik.core.util

import android.content.Context
import java.util.UUID

/**
 * Android implementation of DeviceIdProvider using SharedPreferences.
 */
actual class DeviceIdProvider(
    private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Returns a stable device ID, generating one if it doesn't exist.
     * Format: "Mesh" + 8 hex characters (e.g., "MeshA1B2C3D4")
     */
    actual fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
    }

    /**
     * Returns the user's display name, or a default if not set.
     */
    actual fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, null) ?: DEFAULT_USER_NAME
    }

    /**
     * Sets the user's display name.
     */
    actual fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    private fun generateDeviceId(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        return "Mesh${uuid.take(8).uppercase()}"
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_NAME = "user_name"
        private const val DEFAULT_USER_NAME = "Meshovik User"
    }
}
