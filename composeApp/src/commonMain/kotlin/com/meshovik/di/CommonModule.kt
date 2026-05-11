package com.meshovik.di

import com.meshovik.data.repository.MeshRepository
import org.koin.dsl.module

/**
 * Koin DI module for common (platform-independent) dependencies.
 */
val commonModule = module {
    // Repository
    single { MeshRepository() }
}
