package com.meshovik.di

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.meshovik.ble.manager.BleManager
import com.meshovik.core.util.DeviceIdProvider
import com.meshovik.data.repository.MeshRepository
import com.meshovik.database.MeshovikDatabase
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
    single { DeviceIdProvider(get()) }
    single<MeshovikDatabase> {
        val driver = AndroidSqliteDriver(
            schema = MeshovikDatabase.Schema,
            context = get(),
            name = "meshovik.db"
        )
        MeshovikDatabase(driver)
    }
    single { DeviceIdProvider(get()) }
    // BLE Manager
    single { BleManager(get()) }
    single { FileTransferManager(androidContext(), get()) }
    single { MeshRepository(get(), get()) }
    // ViewModel
    single(createdAtStart = true)
    { MeshViewModel(get(), get(), get(),get(), androidContext()) }
}

