package com.meshovik

import android.app.Application
import com.meshovik.BuildConfig
import com.meshovik.di.androidModule
import com.meshovik.di.commonModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * Meshovik Application class.
 * Initializes Koin DI and Timber logging.
 */
class MeshovikApplication : Application() {
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
    }
}
