package com.meshovik.di

import com.meshovik.ble.manager.BleManager
import com.meshovik.presentation.MeshViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for Android-specific dependencies.
 */
val androidModule = module {
    // BLE Manager
    single { BleManager(androidContext()) }

    // ViewModel
    viewModel { MeshViewModel(get(), get()) }
}
