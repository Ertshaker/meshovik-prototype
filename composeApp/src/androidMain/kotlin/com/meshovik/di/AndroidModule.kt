package com.meshovik.di

import com.meshovik.ble.manager.BleManager
import com.meshovik.core.util.DeviceIdProvider
import com.meshovik.presentation.MeshViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for Android-specific dependencies.
 */
val androidModule = module {
    // Stable device identifier (SharedPreferences-backed, survives app restarts)
    single { DeviceIdProvider(get()) }

    // BLE Manager
    single { BleManager(get()) }

    // ViewModel
    single(createdAtStart = true)
    { MeshViewModel(get(), get(), androidContext()) }
}

