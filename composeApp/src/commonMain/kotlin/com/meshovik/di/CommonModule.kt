package com.meshovik.di

import org.koin.dsl.module

/**
 * Koin DI module for common (platform-independent) dependencies.
 * Note: ConnectionManager and MeshRepository are now in androidMain
 * since they depend on Timber for logging.
 */
val commonModule = module {
    // ConnectionManager and MeshRepository are provided by androidModule
}
