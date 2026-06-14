package com.meshovik.presentation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.Navigator
import com.meshovik.presentation.screens.ChatListScreen
import org.koin.androidx.compose.KoinAndroidContext
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.i("All BLE permissions granted")
        } else {
            Timber.w("Some BLE permissions denied: ${permissions.filterValues { !it }.keys}")
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Timber.i("Bluetooth enabled")
        } else {
            Timber.w("Bluetooth not enabled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestBlePermissions()
        requestFuckingSlavePermissions()
        checkBluetoothEnabled()

        setContent {
            KoinAndroidContext {        // ← добавь эту обёртку
                MeshMessengerApp()
            }
        }
    }
    private fun requestFuckingSlavePermissions() {
        val REQUIRED_PERMISSIONS: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                REQUIRED_PERMISSIONS =
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                REQUIRED_PERMISSIONS =
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                REQUIRED_PERMISSIONS =
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
            } else {
                REQUIRED_PERMISSIONS =
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
            }
        permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }
    private fun requestBlePermissions() {
        // Собираем все необходимые разрешения в один запрос.
        // Разделение на два запроса (launcher + requestPermissions) приводит к тому,
        // что второй запрос игнорируется системой, если первый ещё не завершён.
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): новые BLE разрешения
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                // Android 11 и ниже
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+): NEARBY_WIFI_DEVICES для Wi-Fi Direct
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }

            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.toTypedArray()

        Timber.i("Requesting permissions: ${permissions.toList()}")
        permissionsLauncher.launch(permissions)
    }

    private fun checkBluetoothEnabled() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter?.isEnabled == false) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
        }
    }
}

@Composable
fun MeshMessengerApp() {
    Navigator(ChatListScreen)
}

@Preview
@Composable
fun AppAndroidPreview() {
    MeshMessengerApp()
}
