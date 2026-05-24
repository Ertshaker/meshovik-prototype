package com.meshovik

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class BleScanner {
    @OptIn(ExperimentalUuidApi::class, ExperimentalUuidApi::class)
    fun scan(): Flow<Advertisement> {
        return Scanner {
            filters {
                match {
                    services = listOf(Uuid.Companion.parse("12345678-1234-1234-1234-1234567890ab"))
                }
            }
        }.advertisements
    }
}