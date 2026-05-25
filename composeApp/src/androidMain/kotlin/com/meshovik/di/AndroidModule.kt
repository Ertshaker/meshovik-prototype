package com.meshovik.di

import android.provider.Settings
import com.meshovik.ble.BleCentral
import com.meshovik.ble.BleManager
import com.meshovik.ble.BleScanner
import com.meshovik.presentation.MeshViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for Android-specific dependencies.
 */
val androidModule = module {
    // BLE Scanner - Android implementation (uses MeshovikApplication.context internally)
    factory { BleScanner() }

    // BLE Central - Kable implementation
    single { BleCentral() }

    // BLE Peripheral - Android implementation with stable device name
    single {
        val androidId = Settings.Secure.getString(
            androidContext().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    // BLE Manager - common orchestrator with stable device address
    single {
        val androidId = Settings.Secure.getString(
            androidContext().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        BleManager(
            get(),
            get(),
            get(),
            localDeviceAddressOverride = androidId,
            localDeviceNameOverride = "Meshovik-${androidId.takeLast(4)}"
        )
    }

    // ViewModel
    viewModel { MeshViewModel(get(), get()) }
}
