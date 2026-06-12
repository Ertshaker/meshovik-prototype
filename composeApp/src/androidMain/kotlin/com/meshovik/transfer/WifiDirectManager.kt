package com.meshovik.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ─── Константы ──────────────────────────────────────────────────────────────

/** Максимальное число попыток discoverPeers перед сдачей */
private const val DISCOVERY_MAX_RETRIES = 5

/** Базовая задержка между попытками (мс). Удваивается при каждой попытке. */
private const val DISCOVERY_RETRY_BASE_DELAY_MS = 1_500L

/** Задержка перед повторным вызовом после BUSY (мс) */
private const val DISCOVERY_BUSY_DELAY_MS = 2_000L

/** Задержка перед повторным вызовом после ERROR (мс) */
private const val DISCOVERY_ERROR_DELAY_MS = 3_000L
private const val DISCOVERY_INTERVAL_MS = 35_000L  // ~30-40 сек, как рекомендуют

/**
 * Менеджер Wi-Fi Direct соединений.
 *
 * Отвечает за:
 * - Обнаружение peers с retry-логикой и умными задержками
 * - Отслеживание состояния Wi-Fi P2P (включён/выключен)
 * - Установку P2P соединения
 * - Предоставление информации о соединении (GroupOwner IP, порт)
 *
 * ## Почему discoverPeers возвращает ERROR (reason=0)?
 *
 * Причины (по убыванию частоты):
 * 1. Wi-Fi выключен на устройстве
 * 2. Нет разрешения ACCESS_FINE_LOCATION (Android ≤ 12) или NEARBY_WIFI_DEVICES (Android 13+)
 * 3. Предыдущий discovery ещё не завершён (нужен stopPeerDiscovery перед повторным вызовом)
 * 4. Внутренняя ошибка Wi-Fi стека — помогает пауза + повтор
 *
 * ## Решение
 * - Реальная проверка isWifiP2pEnabled через BroadcastReceiver
 * - stopPeerDiscovery перед каждым новым discoverPeers
 * - Retry с экспоненциальной задержкой
 * - Подробные Timber-логи на каждом шаге
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wifiP2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var periodicDiscoveryJob: Job? = null

    private val _myWifiDirectAddress = MutableStateFlow<String?>(null)
    val myWifiDirectAddress: StateFlow<String?> = _myWifiDirectAddress.asStateFlow()
    private var channel: WifiP2pManager.Channel =
        wifiP2pManager.initialize(context, context.mainLooper, object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                Timber.e("Channel lost → reinitializing")

                wifiP2pManager.initialize(
                    context,
                    context.mainLooper,
                    this
                )
            }
        })

    // ─── Public State ────────────────────────────────────────────────────────

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    /** Список обнаруженных Wi-Fi Direct устройств */
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    /** Информация о текущем P2P соединении (GroupOwner IP и т.д.) */
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    /** true, если P2P соединение активно */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _p2pEnabled = MutableStateFlow(false)
    /** true, если Wi-Fi P2P включён и доступен */
    val p2pEnabled: StateFlow<Boolean> = _p2pEnabled.asStateFlow()

    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    /** Текущее состояние процесса обнаружения */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /**
     * Сообщения для отображения пользователю (например, "Включите Wi-Fi").
     * Подписывайтесь в ViewModel/UI.
     */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // ─── Internal State ──────────────────────────────────────────────────────

    /** Канал для получения информации о соединении после connect() */
    private val connectionInfoChannel = Channel<WifiP2pInfo>(Channel.CONFLATED)
    @Volatile
    private var isDiscoveryRunning = false
    /** Job текущей retry-сессии discovery, чтобы можно было отменить */
    private var discoveryRetryJob: Job? = null

    // ─── BroadcastReceiver ───────────────────────────────────────────────────

    init {
        ensureDiscovering()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _p2pEnabled.value = enabled

                    if (enabled) {
                        Timber.i("Wi-Fi P2P ENABLED")
                    } else {
                        Timber.w("Wi-Fi P2P DISABLED — discovery невозможен. Попросите пользователя включить Wi-Fi.")
                        _peers.value = emptyList()
                        _discoveryState.value = DiscoveryState.IDLE
                        emitUserMessage("Включите Wi-Fi для обнаружения устройств")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Timber.d("Wi-Fi Direct WIFI_P2P_PEERS_CHANGED_ACTION received — запрашиваем список peers")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleConnectionChanged(intent)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                }
            }
        }
    }

    private fun handleConnectionChanged(intent: Intent) {
        val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                WifiP2pManager.EXTRA_NETWORK_INFO,
                NetworkInfo::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        Timber.d("Wi-Fi Direct WIFI_P2P_CONNECTION_CHANGED_ACTION: isConnected=${networkInfo?.isConnected}")

        if (networkInfo?.isConnected == true) {
            _isConnected.value = true
            Timber.i("Wi-Fi Direct connected")
            requestConnectionInfo()
        } else {
            _isConnected.value = false
            _connectionInfo.value = null
            Timber.i("Wi-Fi Direct disconnected")
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    private val groupMutex = Mutex()
    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun register() {
        context.registerReceiver(receiver, intentFilter)
        startPeriodicDiscovery()
        Timber.i("WifiDirectManager registered")
    }

    fun unregister() {
        stopDiscovery()
        stopPeriodicDiscovery()
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Timber.w(e, "WifiDirectManager: receiver not registered")
        }
        Timber.i("WifiDirectManager unregistered")
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    /**
     * Запустить обнаружение Wi-Fi Direct peers.
     *
     * Выполняет предварительные проверки:
     * - Wi-Fi P2P включён
     * - Нет активного discovery (или останавливает его)
     *
     * При ошибке автоматически повторяет попытку с экспоненциальной задержкой
     * (до [DISCOVERY_MAX_RETRIES] раз).
     */
    fun discoverPeers() {
        if (isDiscoveryRunning) {
            Timber.d("Discovery уже запущен — пропускаем")
            return
        }

        discoveryRetryJob?.cancel()

        discoveryRetryJob = scope.launch {
            isDiscoveryRunning = true
            try {
                discoverPeersWithRetry()
            } finally {
                isDiscoveryRunning = false
            }
        }
    }

    /**
     * Остановить текущий discovery.
     */
    fun stopDiscovery() {
        discoveryRetryJob?.cancel()
        discoveryRetryJob = null

        if (_discoveryState.value == DiscoveryState.DISCOVERING) {
            wifiP2pManager.stopPeerDiscovery(channel, object : ActionListener {
                override fun onSuccess() {
                    Timber.d("stopPeerDiscovery: success")
                    _discoveryState.value = DiscoveryState.IDLE
                }
                override fun onFailure(reason: Int) {
                    Timber.w("stopPeerDiscovery failed: ${reasonText(reason)}")
                    _discoveryState.value = DiscoveryState.IDLE
                }
            })
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun ensureGroupAsOwner(): Boolean = groupMutex.withLock {
        Timber.i("ensureGroupAsOwner: начинаем процесс становления Group Owner")

        removeGroup()

        Thread.sleep(1500)
        repeat(3) { attempt ->
            Timber.i("ensureGroupAsOwner: попытка $attempt создания группы")

            val created = createGroup()  // использует твою текущую реализацию

            if (created) {
                // Ждём реального подтверждения от системы
                val info = withTimeoutOrNull(12_000) {
                    connectionInfo.first { info ->
                        info?.isGroupOwner == true && info.groupFormed
                    }
                }

                if (info != null) {
                    Timber.i("✅ ensureGroupAsOwner: успешно стали Group Owner. IP=${info.groupOwnerAddress?.hostAddress}")
                    return true
                } else {
                    Timber.w("ensureGroupAsOwner: createGroup success, но не дождались isGroupOwner")
                }
            }

            delay(2000L * (attempt + 1)) // экспоненциальная задержка
        }

        Timber.e("❌ ensureGroupAsOwner: не удалось стать Group Owner после 3 попыток")
        return false
    }
    /**
     * Проверяет, что все условия для discovery выполнены, и запускает его.
     * При ошибке повторяет с задержкой.
     */
    private suspend fun discoverPeersWithRetry() {
        // ── Шаг 1: Проверка Wi-Fi P2P ──────────────────────────────────────
        if (!hasPermissions()) {
            Timber.w("Нет разрешений для Wi-Fi Direct")
            emitUserMessage("Дайте разрешения для Wi-Fi")
            _discoveryState.value = DiscoveryState.FAILED
            return
        }

        if (!isLocationEnabled()) {
            Timber.w("GPS выключен → discovery не сработает")
            emitUserMessage("Включите геолокацию (GPS)")
            _discoveryState.value = DiscoveryState.FAILED
            return
        }

        if (!_p2pEnabled.value) {
            Timber.w("discoverPeers: Wi-Fi P2P выключен. Ждём включения...")
            delay(2000)
            _discoveryState.value = DiscoveryState.WAITING_FOR_WIFI
            emitUserMessage("Включите Wi-Fi для обнаружения устройств")

            // Ждём включения P2P (максимум 30 секунд)
            var waited = 0
            while (!_p2pEnabled.value && waited < 60) {
                delay(500)
                waited++
            }

            if (!_p2pEnabled.value) {
                Timber.e("discoverPeers: Wi-Fi P2P так и не включился за 30 секунд. Отмена.")
                _discoveryState.value = DiscoveryState.FAILED
                emitUserMessage("Wi-Fi Direct недоступен. Проверьте настройки Wi-Fi.")
                return
            }
            Timber.i("discoverPeers: Wi-Fi P2P включился, продолжаем")
        }

        // ── Шаг 2: Остановить предыдущий discovery (избегаем BUSY) ─────────
        if (_discoveryState.value == DiscoveryState.DISCOVERING) {
            Timber.d("discoverPeers: останавливаем предыдущий discovery перед новым запуском")
            stopPeerDiscoverySync()
            delay(500) // небольшая пауза после остановки
        }

        // ── Шаг 3: Retry-цикл ───────────────────────────────────────────────
        var attempt = 0
        while (attempt < DISCOVERY_MAX_RETRIES) {
            attempt++
            Timber.i("discoverPeers: попытка $attempt/$DISCOVERY_MAX_RETRIES")
            _discoveryState.value = DiscoveryState.STARTING

            val result = startDiscoveryOnce()

            when (result) {
                DiscoveryResult.SUCCESS -> {
                    Timber.i("discoverPeers: discovery запущен успешно (попытка $attempt)")
                    _discoveryState.value = DiscoveryState.DISCOVERING
                    return
                }

                DiscoveryResult.BUSY -> {
                    Timber.w("discoverPeers: BUSY (попытка $attempt) — ждём ${DISCOVERY_BUSY_DELAY_MS}мс")
                    _discoveryState.value = DiscoveryState.RETRYING
                    delay(DISCOVERY_BUSY_DELAY_MS)
                    // При BUSY — сначала останавливаем, потом пробуем снова
                    stopPeerDiscoverySync()
                }

                DiscoveryResult.P2P_UNSUPPORTED -> {
                    Timber.e("discoverPeers: Wi-Fi Direct не поддерживается на этом устройстве!")
                    _discoveryState.value = DiscoveryState.FAILED
                    emitUserMessage("Ваше устройство не поддерживает Wi-Fi Direct")
                    return
                }

                DiscoveryResult.ERROR -> {
                    stopPeerDiscoverySync()
                    val delayMs = DISCOVERY_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
                    Timber.w("discoverPeers: ERROR (попытка $attempt) — ждём ${delayMs}мс перед повтором")
                    _discoveryState.value = DiscoveryState.RETRYING
                    emitUserMessage("Ошибка поиска устройств, повторяем...")
                    delay(delayMs)
                }
            }
        }

        // Все попытки исчерпаны
        Timber.e("discoverPeers: все $DISCOVERY_MAX_RETRIES попыток провалились")
        _discoveryState.value = DiscoveryState.FAILED
        emitUserMessage("Не удалось запустить поиск устройств. Проверьте Wi-Fi.")
    }
    fun startPeriodicDiscovery() {
        stopPeriodicDiscovery()
        periodicDiscoveryJob = scope.launch {
            while (true) {
                if (_p2pEnabled.value && hasPermissions() && isLocationEnabled()) {
                    ensureDiscovering()  // или напрямую discoverPeers()
                    Timber.i("Wi-Fi Direct начал дисковерить")
                }
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    fun stopPeriodicDiscovery() {
        periodicDiscoveryJob?.cancel()
    }
    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    /**
     * Один вызов discoverPeers без retry.
     * Возвращает результат через sealed class.
     */
    private suspend fun startDiscoveryOnce(): DiscoveryResult =
        suspendCancellableCoroutine { cont ->
            wifiP2pManager.discoverPeers(channel, object : ActionListener {
                override fun onSuccess() {
                    Timber.d("discoverPeers.onSuccess()")
                    if (cont.isActive) cont.resume(DiscoveryResult.SUCCESS)
                }

                override fun onFailure(reason: Int) {
                    val text = reasonText(reason)
                    Timber.w("discoverPeers.onFailure: $text (code=$reason)")

                    val result = when (reason) {
                        WifiP2pManager.BUSY -> DiscoveryResult.BUSY
                        WifiP2pManager.P2P_UNSUPPORTED -> DiscoveryResult.P2P_UNSUPPORTED
                        WifiP2pManager.ERROR -> DiscoveryResult.ERROR
                        else -> DiscoveryResult.ERROR
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    /**
     * Синхронная (suspend) остановка discovery.
     * Нужна перед повторным запуском, чтобы избежать BUSY.
     */
    private suspend fun stopPeerDiscoverySync() {
        suspendCancellableCoroutine<Unit> { cont ->
            wifiP2pManager.stopPeerDiscovery(channel, object : ActionListener {
                override fun onSuccess() {
                    Timber.d("stopPeerDiscovery: OK")
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    // Не критично — продолжаем в любом случае
                    Timber.w("stopPeerDiscovery failed: ${reasonText(reason)} — продолжаем")
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    // ─── Connection ──────────────────────────────────────────────────────────

    /**
     * Подключиться к peer по MAC-адресу Wi-Fi Direct.
     *
     * @param deviceAddress MAC-адрес Wi-Fi Direct устройства (например, "AA:BB:CC:DD:EE:FF")
     * @return [WifiP2pInfo] с информацией о соединении (GroupOwner IP и т.д.)
     */
    suspend fun connectToPeer(deviceAddress: String): WifiP2pInfo {
        Timber.i("connectToPeer: $deviceAddress")

        return suspendCancellableCoroutine { cont ->
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                // groupOwnerIntent = 0  // 0 = предпочитать быть client'ом
            }

            wifiP2pManager.connect(channel, config, object : ActionListener {
                override fun onSuccess() {
                    Timber.i("connect.onSuccess() — запрос отправлен, ждём WIFI_P2P_CONNECTION_CHANGED_ACTION")

                    // Ждём реального соединения с таймаутом
                    scope.launch {
                        try {
                            val info = withTimeoutOrNull(25_000) {
                                connectionInfoChannel.receive()
                            }

                            if (info != null && info.groupFormed) {
                                Timber.i("connectToPeer: соединение установлено! GroupOwner=${info.isGroupOwner}, IP=${info.groupOwnerAddress?.hostAddress}")
                                cont.resume(info)
                            } else {
                                cont.resumeWithException(Exception("Соединение установлено, но groupFormed=false"))
                            }
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val msg = "connectToPeer failed: ${reasonText(reason)}"
                    Timber.e(msg)
                    cont.resumeWithException(Exception(msg))
                }
            })
        }
    }

    /**
     * Отключиться от текущей P2P группы.
     */
    fun disconnect() {
        Timber.i("Wi-Fi Direct disconnect: удаляем P2P группу")
        wifiP2pManager.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Timber.i("Wi-Fi Direct removeGroup: success")
                _isConnected.value = false
                _connectionInfo.value = null
            }

            override fun onFailure(reason: Int) {
                Timber.w("Wi-Fi Direct removeGroup failed: ${reasonText(reason)}")
            }
        })
    }

    // ─── Ensure Discoverable ─────────────────────────────────────────────────

    /**
     * Проверяет готовность к discovery и запускает его, если нужно.
     *
     * Вызывайте этот метод вместо [discoverPeers] для максимальной надёжности.
     * Метод:
     * 1. Проверяет, включён ли Wi-Fi P2P
     * 2. Если уже идёт discovery — ничего не делает
     * 3. Если нет — запускает с retry
     *
     * @return true, если discovery уже идёт или успешно запущен
     */
    fun ensureDiscovering() {
        when (_discoveryState.value) {
            DiscoveryState.DISCOVERING -> {
                Timber.d("Wi-Fi Direct ensureDiscovering: discovery уже активен, пропускаем")
            }
            DiscoveryState.STARTING, DiscoveryState.RETRYING -> {
                Timber.d("Wi-Fi Direct ensureDiscovering: discovery запускается, ждём")
            }
            else -> {
                Timber.i("Wi-Fi Direct ensureDiscovering: запускаем discovery")
                discoverPeers()
            }
        }
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private fun requestPeers() {
        wifiP2pManager.requestPeers(channel) { peerList ->
            val devices = peerList.deviceList.toList()
            _peers.value = devices

            Timber.i("Wi-Fi Direct requestPeers: найдено ${devices.size} устройств")
            if (devices.isEmpty()) {
                Timber.w("Wi-Fi Direct PEERS_CHANGED пришёл, но список пустой!")
            }

            devices.forEach { d ->
                Timber.i("Wi-Fi Direct Peer: ${d.deviceName} | ${d.deviceAddress} | status=${deviceStatusText(d.status)}")
            }
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
                    "Wi-fi Direct Connection info: isGroupOwner=${info.isGroupOwner}, " +
                    "ownerAddress=${info.groupOwnerAddress?.hostAddress}"
                )
            } else {
                Timber.w("Wi-fi Direct requestConnectionInfo: info == null")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun createGroup(): Boolean {
        Timber.i("Wi-Fi Direct createGroup: Я ЕЁ НАХУЙ УДАЛИЛ")
        return suspendCancellableCoroutine { cont ->
            val config =
                WifiP2pConfig.Builder()
                    // Один из двух вариантов должен сработать:
                    .setNetworkName("DIRECT-Mesh-1488")
                    .setPassphrase("skibididopdop")
                    .build()

            wifiP2pManager.createGroup(channel, config, object : ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct createGroup: success")
                    cont.resume(true)
                }
                override fun onFailure(reason: Int) {
                    Timber.e("Wi-Fi Direct createGroup failed: ${reasonText(reason)}")
                    cont.resume(false)
                }
            })
        }
    }

    suspend fun removeGroup(): Boolean {
        return suspendCancellableCoroutine { cont ->
            wifiP2pManager.removeGroup(channel, object : ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct removeGroup: success")
                    _isConnected.value = false
                    _connectionInfo.value = null
                    cont.resume(true)
                }
                override fun onFailure(reason: Int) {
                    Timber.w("Wi-Fi Direct removeGroup failed: ${reasonText(reason)}")
                    _isConnected.value = false
                    _connectionInfo.value = null
                    cont.resume(true)
                }
            })
        }
    }
    private fun emitUserMessage(message: String) {
        scope.launch {
            _userMessages.emit(message)
        }
    }

    // ─── Static Helpers ──────────────────────────────────────────────────────

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        else -> "UNKNOWN($reason)"
    }

    private fun deviceStatusText(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

// ─── Enums ───────────────────────────────────────────────────────────────────

/** Состояние процесса обнаружения peers */
enum class DiscoveryState {
    /** Discovery не запущен */
    IDLE,
    /** Ожидаем включения Wi-Fi */
    WAITING_FOR_WIFI,
    /** Отправляем команду discoverPeers */
    STARTING,
    /** Discovery активен, ищем устройства */
    DISCOVERING,
    /** Ошибка, ждём перед повтором */
    RETRYING,
    /** Все попытки исчерпаны */
    FAILED
}

/** Результат одного вызова discoverPeers */
private enum class DiscoveryResult {
    SUCCESS,
    BUSY,
    ERROR,
    P2P_UNSUPPORTED
}
