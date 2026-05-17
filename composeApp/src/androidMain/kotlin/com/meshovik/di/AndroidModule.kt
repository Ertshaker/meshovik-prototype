package com.meshovik.di

import com.meshovik.ble.manager.BleManager
import com.meshovik.data.remote.transport.BleTransport
import com.meshovik.data.remote.transport.ConnectionManager
import com.meshovik.data.repository.MeshRepository
import com.meshovik.presentation.MeshViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

/**
 * Koin DI module for Android-specific dependencies.
 */
val androidModule = module {
    // Connection Manager - orchestrates all transports
    single { ConnectionManager() }

    // Repository - depends on ConnectionManager for transport
    single { MeshRepository(get()) }

    // BLE Manager (kept for backward compatibility and advanced operations)
    single { BleManager(androidContext()) }

    // BLE Transport - adapts BleManager to MessageTransport interface
    single { BleTransport(get()) }

    // ViewModel
    viewModel { MeshViewModel(get(), get()) }
}

/**
 * Extension function to initialize transports and register them with ConnectionManager.
 * Call this during application startup with the application's CoroutineScope.
 */
fun ConnectionManager.registerAndroidTransports(
    bleTransport: BleTransport,
    scope: CoroutineScope
) {
    scope.launch {
        try {
            registerTransport(bleTransport)
            Timber.i("BLE transport registered with ConnectionManager")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register BLE transport")
        }
    }
}
