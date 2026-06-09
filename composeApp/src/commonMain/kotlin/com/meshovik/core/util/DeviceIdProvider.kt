package com.meshovik.core.util

/**
 * Provides a stable device identifier that persists across app restarts.
 * Platform-specific implementations handle storage (SharedPreferences on Android, UserDefaults on iOS).
 */
expect class DeviceIdProvider {
    /**
     * Returns a stable device ID, generating one if it doesn't exist.
     * Format: "Mesh" + 8 hex characters (e.g., "MeshA1B2C3D4")
     */
    fun getDeviceId(): String

    /**
     * Returns the user's display name, or a default if not set.
     */
    fun getUserName(): String

    /**
     * Sets the user's display name.
     */
    fun setUserName(name: String)
}
