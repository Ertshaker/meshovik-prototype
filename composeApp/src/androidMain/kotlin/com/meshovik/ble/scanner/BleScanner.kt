package com.meshovik.ble.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.meshovik.ble.model.BleConstants
import com.meshovik.domain.entity.MeshDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import kotlin.time.Clock

/**
 * BLE Scanner for discovering mesh devices.
 * Uses Android BLE scanning API with custom service UUID filter.
 */
class BleScanner(
    private val context: Context
) {
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var isScanning = false

    private var currentScanCallback: ScanCallback? = null

    /**
     * Checks if BLE is supported on this device.
     */
    fun isBleSupported(): Boolean = bluetoothAdapter != null

    /**
     * Checks if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Starts scanning for mesh devices and returns a Flow of discovered devices.
     * The scan runs until the flow is cancelled or stopScanning() is called.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevices(): Flow<MeshDevice> = callbackFlow {
        if (!isBleSupported()) {
            Timber.e("BLE is not supported on this device")
            close()
            return@callbackFlow
        }

        if (!isBluetoothEnabled()) {
            Timber.e("Bluetooth is not enabled")
            close()
            return@callbackFlow
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Timber.e("BLE Scanner is null")
            close()
            return@callbackFlow
        }

        // Filter for our custom mesh service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val device = result.device
                val meshDevice = MeshDevice(
                    id = device.address,
                    name = device.name ?: "Unknown Device",
                    address = device.address,
                    rssi = result.rssi,
                    lastSeen = Clock.System.now(),
                    isOnline = true,
                    hopCount = 0
                )
                trySend(meshDevice)
                Timber.d("Discovered device: ${meshDevice.name} (${meshDevice.address}) RSSI: ${meshDevice.rssi}")
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                super.onBatchScanResults(results)
                results.forEach { result ->
                    trySend(createMeshDevice(result))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Timber.e("BLE scan failed with error code: $errorCode")
            }

            private fun createMeshDevice(result: ScanResult): MeshDevice {
                val device = result.device
                return MeshDevice(
                    id = device.address,
                    name = device.name ?: "Unknown Device",
                    address = device.address,
                    rssi = result.rssi,
                    lastSeen = Clock.System.now(),
                    isOnline = true,
                    hopCount = 0
                )
            }
        }

        currentScanCallback = scanCallback

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Timber.i("BLE scan started")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing BLE permissions")
            close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start BLE scan")
            close()
        }

        awaitClose {
            try {
                scanner.stopScan(scanCallback)
                isScanning = false
                Timber.i("BLE scan stopped")
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop BLE scan")
            }
        }
    }

    /**
     * Stops the current scan if running.
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val callback = currentScanCallback ?: return
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (scanner != null && isScanning) {
            try {
                scanner.stopScan(callback)
                Timber.i("BLE Scan stopped successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping BLE scan")
            }
        }

        currentScanCallback = null
        isScanning = false
    }
}
