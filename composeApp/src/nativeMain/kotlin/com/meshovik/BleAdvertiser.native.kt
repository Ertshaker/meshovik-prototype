package com.meshovik

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual class BleAdvertiser {
    actual val advertisingState: Flow<Boolean> = flowOf(false)

    actual fun startAdvertising() {
        // No-op on native (iOS)
    }

    actual fun stopAdvertising() {
        // No-op on native (iOS)
    }
}