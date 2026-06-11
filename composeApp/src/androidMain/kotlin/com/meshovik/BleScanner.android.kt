package com.meshovik

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

actual class BleScanner(
    private val context: Context
) {
    private var scanScope: CoroutineScope? = null
    private val advertisementsFlow = MutableSharedFlow<Advertisement>(extraBufferCapacity = 64)

    @OptIn(ExperimentalUuidApi::class)
    actual fun scan(): Flow<Advertisement> {
        stopScanning()
        scanScope = CoroutineScope(Dispatchers.IO)
        scanScope?.launch {
            try {
                val scanner = Scanner {
                    filters {
                        match {
                            services = listOf(Uuid.parse("A1B2C3D4-E5F6-7890-A1B2-C3D4E5F67890"))
                        }
                    }
                }
                scanner.advertisements.collect { adv ->
                    advertisementsFlow.emit(adv)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, don't log as error
                Timber.d("BLE scanning cancelled")
            } catch (e: Exception) {
                Timber.e(e, "BLE scanning error")
            }
        }
        return advertisementsFlow
    }

    actual fun isBleSupported(): Boolean {
        return BluetoothAdapter.getDefaultAdapter() != null
    }

    actual fun isBluetoothEnabled(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        // Use both state and isEnabled for maximum reliability across all devices
        val state = adapter.state
        return state == android.bluetooth.BluetoothAdapter.STATE_ON ||
               state == android.bluetooth.BluetoothAdapter.STATE_TURNING_ON ||
               adapter.isEnabled
    }

    actual fun stopScanning() {
        scanScope?.cancel()
        scanScope = null
    }
}
