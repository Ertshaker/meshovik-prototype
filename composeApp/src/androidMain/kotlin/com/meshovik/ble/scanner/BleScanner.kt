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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import kotlin.time.Clock

/**
 * BLE Scanner for discovering mesh devices.
 * Uses Android BLE scanning API with custom service UUID filter.
 * All scanning operations run on Dispatchers.IO to prevent ANR.
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

    // StateFlow for external observers to track scanning state
    private val _isScanningState = MutableStateFlow(false)
    val isScanningState: StateFlow<Boolean> = _isScanningState.asStateFlow()

    private var currentScanCallback: ScanCallback? = null

    // Track recently seen devices with timestamps to avoid duplicate emissions
    // Using a map of address -> lastSeenTime instead of a simple set
    private val seenDevices = mutableMapOf<String, Long>()
    
    // Time window for considering a device as "recently seen" (5 seconds)
    private val DEDUP_WINDOW_MS = 5_000L

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
     * All scanning operations run on Dispatchers.IO.
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
                val address = device.address
                val now = System.currentTimeMillis()
                
                // Deduplicate: only emit if we haven't seen this device within the dedup window
                val lastSeen = seenDevices[address] ?: 0L
                if (now - lastSeen < DEDUP_WINDOW_MS) {
                    return
                }
                seenDevices[address] = now
                
                val deviceName = extractDeviceName(result)
                val meshDevice = MeshDevice(
                    id = address,
                    name = deviceName,
                    address = address,
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
                // Skip batch results - we handle everything in onScanResult to avoid duplicates
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Timber.e("BLE scan failed with error code: $errorCode")
            }

            /**
             * Извлекает имя устройства из Service Data, fallback на device.name.
             */
            private fun extractDeviceName(result: ScanResult): String {
                val scanRecord = result.scanRecord
                
                // First, try to extract from Service Data for our mesh UUID
                if (scanRecord != null) {
                    val serviceData = scanRecord.serviceData
                    val meshUuid = ParcelUuid(BleConstants.MESH_SERVICE_UUID)
                    val serviceDataBytes = serviceData[meshUuid]
                    if (serviceDataBytes != null) {
                        try {
                            val name = String(serviceDataBytes, Charsets.UTF_8)
                            if (name.isNotBlank()) {
                                Timber.d("Device name from service data: $name")
                                return name
                            }
                        } catch (e: Exception) {
                            Timber.w("Failed to parse device name from service data: $e")
                        }
                    }
                    
                    // Fallback: try standard device name from scan record
                    val localName = scanRecord.deviceName
                    if (!localName.isNullOrBlank()) {
                        return localName
                    }
                }

                // Last fallback: try Bluetooth device name (may be null for unconnected devices)
                val device = result.device
                val standardName = device.name
                if (!standardName.isNullOrBlank()) {
                    return standardName
                }

                return "Unknown Device"
            }
        }

        currentScanCallback = scanCallback
        // Don't clear seenDevices here - keep deduplication across scan restarts
        // This prevents re-emitting the same devices immediately after scan restart

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            _isScanningState.value = true
            Timber.i("BLE scan started with filter for mesh service UUID")
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
                _isScanningState.value = false
                Timber.i("BLE scan stopped")
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop BLE scan")
            }
        }
    }.flowOn(Dispatchers.IO)

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
        _isScanningState.value = false
        // Clean old entries from seenDevices (older than 30 seconds)
        val now = System.currentTimeMillis()
        seenDevices.entries.removeAll { (_, timestamp) ->
            now - timestamp > 30_000L
        }
        Timber.d("Cleaned seenDevices cache, ${seenDevices.size} devices remaining")
    }
}
