package com.meshovik

import android.app.Application
import com.meshovik.BuildConfig
import com.meshovik.data.remote.transport.ConnectionManager
import com.meshovik.data.remote.transport.BleTransport
import com.meshovik.di.androidModule
import com.meshovik.di.commonModule
import com.meshovik.di.registerAndroidTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * Meshovik Application class.
 * Initializes Koin DI and Timber logging.
 */
class MeshovikApplication : Application(), KoinComponent {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("Meshovik Application started")

        // Initialize Koin
        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@MeshovikApplication)
            modules(
                commonModule,
                androidModule
            )
        }

        Timber.i("Koin initialized successfully")

        // Initialize transport layer
        initializeTransport()
    }

    /**
     * Initializes the BLE transport and registers it with ConnectionManager.
     */
    private fun initializeTransport() {
        val connectionManager: ConnectionManager by inject()
        val bleTransport: BleTransport by inject()

        applicationScope.launch {
            try {
                connectionManager.registerAndroidTransports(bleTransport, applicationScope)
                Timber.i("Transport layer initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize transport layer")
            }
        }
    }
}
