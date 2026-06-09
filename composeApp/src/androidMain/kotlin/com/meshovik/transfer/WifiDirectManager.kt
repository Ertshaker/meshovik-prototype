package com.meshovik.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Менеджер Wi-Fi Direct соединений.
 * Отвечает за:
 * - Обнаружение peers
 * - Установку P2P соединения
 * - Предоставление информации о соединении (GroupOwner IP, порт)
 *
 * Используется FileTransferManager для передачи файлов.
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wifiP2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private val channel: WifiP2pManager.Channel =
        wifiP2pManager.initialize(context, context.mainLooper, null)

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Канал для получения информации о соединении после connect() */
    private val connectionInfoChannel = Channel<WifiP2pInfo>(Channel.CONFLATED)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Timber.d("Wi-Fi P2P state: $state")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            android.net.NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        _isConnected.value = true
                        requestConnectionInfo()
                    } else {
                        _isConnected.value = false
                        _connectionInfo.value = null
                        Timber.i("Wi-Fi Direct disconnected")
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Timber.d("This device changed")
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun register() {
        context.registerReceiver(receiver, intentFilter)
        Timber.i("WifiDirectManager registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Timber.w(e, "WifiDirectManager: receiver not registered")
        }
    }

    /**
     * Начать обнаружение Wi-Fi Direct peers.
     */
    fun discoverPeers() {
        wifiP2pManager.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {
                Timber.i("Wi-Fi Direct peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Wi-Fi Direct peer discovery failed: reason=$reason")
            }
        })
    }

    /**
     * Подключиться к peer по MAC-адресу Wi-Fi Direct.
     */
    suspend fun connectToPeer(deviceAddress: String): WifiP2pInfo {
        return suspendCancellableCoroutine { cont ->
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
            }

            wifiP2pManager.connect(channel, config, object : ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct connect initiated to $deviceAddress")
                    // Ждём WIFI_P2P_CONNECTION_CHANGED_ACTION через BroadcastReceiver
                    scope.launch {
                        try {
                            val info = connectionInfoChannel.receive()
                            cont.resume(info)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val msg = "Wi-Fi Direct connect failed: reason=$reason"
                    Timber.e(msg)
                    cont.resumeWithException(Exception(msg))
                }
            })
        }
    }

    /**
     * Отключиться от текущей группы.
     */
    fun disconnect() {
        wifiP2pManager.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Timber.i("Wi-Fi Direct group removed")
                _isConnected.value = false
                _connectionInfo.value = null
            }

            override fun onFailure(reason: Int) {
                Timber.w("Wi-Fi Direct removeGroup failed: reason=$reason")
            }
        })
    }

    private fun requestPeers() {
        wifiP2pManager.requestPeers(channel) { peerList ->
            _peers.value = peerList.deviceList.toList()
            Timber.d("Wi-Fi Direct peers updated: ${peerList.deviceList.size} devices")
        }
    }

    private fun requestConnectionInfo() {
        wifiP2pManager.requestConnectionInfo(channel) { info ->
            if (info != null) {
                _connectionInfo.value = info
                scope.launch {
                    connectionInfoChannel.send(info)
                }
                Timber.i(
                    "Wi-Fi Direct connection info: groupOwner=${info.isGroupOwner}, " +
                    "ownerAddress=${info.groupOwnerAddress?.hostAddress}"
                )
            }
        }
    }
}
