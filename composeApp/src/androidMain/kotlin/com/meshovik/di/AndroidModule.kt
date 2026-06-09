package com.meshovik.di

import com.meshovik.ble.manager.BleManager
import com.meshovik.core.util.DeviceIdProvider
import com.meshovik.presentation.MeshViewModel
import com.meshovik.transfer.FileTransferManager
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for Android-specific dependencies.
 */
val androidModule = module {
    // Stable device identifier (SharedPreferences-backed, survives app restarts)
    single { DeviceIdProvider(androidContext()) }

    // BLE Manager
    single { BleManager(androidContext()) }

    // File Transfer Manager (Wi-Fi Direct)
    single { FileTransferManager(androidContext()) }

    // ViewModel
    viewModel { MeshViewModel(get(), get(), get(), androidContext()) }
}
