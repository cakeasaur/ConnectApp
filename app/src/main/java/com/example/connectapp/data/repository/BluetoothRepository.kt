package com.example.connectapp.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.BluetoothClient
import com.example.connectapp.utils.AlertEngine
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BluetoothRepository(
    private val appContext: Context,
    private val client: BluetoothClient = BluetoothClient()
) {

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Метка времени последнего пакета. Нужно для UI-индикатора «жив ли поток». */
    private val _lastPacketAt = MutableStateFlow<Long?>(null)
    val lastPacketAt: StateFlow<Long?> = _lastPacketAt.asStateFlow()

    private val _devices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceItem>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private var connectionJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    // Раньше был MainScope() — это привязывало discovery-таймаут к Main-потоку
    // и заставляло слой данных зависеть от UI-диспетчера. Теперь — независимый
    // SupervisorJob на IO, чтобы падение одного job'а не утаскивало остальные.
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * "Намерение отключиться" — флаг на ТЕКУЩУЮ connect-сессию. Объект
     * пересоздаётся в [connect], так что новая сессия не видит флаг
     * предыдущей. Раньше был просто `var Boolean` и при сценарии
     * `disconnect() → быстрый connect()` старая reconnect-петля могла
     * успеть прочитать `false` от нового вызова и продолжить тыкать
     * старый адрес.
     */
    private class DisconnectIntent { @Volatile var requested: Boolean = false }
    @Volatile private var currentIntent: DisconnectIntent? = null

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceItem> {
        // На API 31+ без BLUETOOTH_CONNECT бросает SecurityException;
        // на API <31 та же ситуация без BLUETOOTH. Сначала явно проверяем
        // permission — runCatching оставлен как страховка от вендорных багов.
        if (!hasBluetoothConnectPermission()) return emptyList()
        return runCatching {
            adapter?.bondedDevices?.map { it.toItem(bonded = true) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(appContext, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Publishes the current bonded device list without starting discovery. */
    fun refreshBonded() {
        _devices.value = bondedDevices()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        if (_scanning.value) return

        _devices.value = bondedDevices()
        registerReceiver()
        if (a.isDiscovering) a.cancelDiscovery()
        if (a.startDiscovery()) {
            _scanning.value = true
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = internalScope.launch {
                delay(Constants.DISCOVERY_TIMEOUT_MS)
                stopDiscovery()
            }
        } else {
            unregisterReceiver()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        unregisterReceiver()
        _scanning.value = false
    }

    /**
     * Connects to [address] and keeps the connection alive with automatic
     * reconnection on unexpected drops (up to infinite retries, every
     * [Constants.RECONNECT_DELAY_MS] ms).
     *
     * Reconnection is skipped if [disconnect] or [release] was called first.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, scope: CoroutineScope) {
        val a = adapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        // Своё намерение для этой сессии — не делим shared boolean со старой,
        // которая могла ещё крутиться в reconnect-петле.
        val intent = DisconnectIntent()
        currentIntent?.requested = true   // глушим старую reconnect-петлю
        currentIntent = intent
        stopDiscovery()

        // Дожидаемся завершения старого job ДО старта новой сессии — иначе
        // его finally дёрнет client.close() уже ПОСЛЕ того как новая сессия
        // перезапишет client.socket, и закроет чужой сокет. Тот же класс бага,
        // что починен в WifiRepository (см. коммент там).
        connectionJob?.cancelAndJoin()
        connectionJob = null
        // На случай Error/Disconnected без disconnect() — сокет мог остаться открыт.
        runCatching { client.close() }

        connectionJob = scope.launch {
            var attempt = 0
            var wasConnected = false
            try {
                while (isActive && !intent.requested) {
                    if (attempt == 0) {
                        _state.value = ConnectionState.Connecting
                    } else {
                        // Сразу отражаем «переподключение», иначе UI висит в Connected
                        // всё время delay() и пользователь не понимает, что связь упала.
                        _state.value = ConnectionState.Reconnecting(attempt)
                        delay(Constants.RECONNECT_DELAY_MS)
                    }
                    if (!isActive || intent.requested) break

                    var gotData = false
                    try {
                        client.connect(a, address)
                        AlertEngine.clearEvents()
                        _state.value = ConnectionState.Connected
                        wasConnected = true

                        // Blocks until the remote closes or an I/O error occurs.
                        client.incoming().collect {
                            // Счётчик попыток сбрасываем только когда РЕАЛЬНО пришли
                            // данные — иначе «принял и сразу закрыл» сбрасывал бы его
                            // на каждом цикле, и реконнект крутился бы вечно.
                            if (!gotData) { gotData = true; attempt = 0 }
                            _lastPacketAt.value = System.currentTimeMillis()
                            _incoming.emit(it)
                        }

                    } catch (e: CancellationException) {
                        // Propagate so the outer try-catch can clean up.
                        throw e
                    } catch (t: Throwable) {
                        if (!wasConnected) {
                            // First connection attempt failed — show error, no retry.
                            _state.value = ConnectionState.Error(t.message ?: "Connect failed")
                            return@launch
                        }
                        // Was connected before — retry after delay.
                    } finally {
                        // Always close the socket, even on cancellation.
                        withContext(NonCancellable) {
                            runCatching { client.close() }
                        }
                    }

                    if (!intent.requested) attempt++
                    // Соединение установилось, но данных не было и оно оборвалось —
                    // это не «связь моргнула», а устройство, которое принимает RFCOMM
                    // и тут же закрывает (подключились к гарнитуре вместо платы, или
                    // модуль занят). Не крутим вечно: после N подряд таких обрывов
                    // останавливаемся с понятной ошибкой.
                    if (!intent.requested && !gotData && attempt >= Constants.MAX_RECONNECT_ATTEMPTS) {
                        _state.value = ConnectionState.Error(
                            "Связь постоянно обрывается — проверьте, что подключаетесь к плате (не к гарнитуре) и что она свободна"
                        )
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                // External cancellation (manual disconnect or viewModelScope cleared).
            } finally {
                withContext(NonCancellable) {
                    if (intent.requested) _state.value = ConnectionState.Idle
                }
            }
        }
    }

    /** Возвращает Result, чтобы ViewModel могла показать пользователю ошибку отправки. */
    suspend fun send(payload: String): Result<Unit> = runCatching { client.send(payload) }
    suspend fun sendBytes(bytes: ByteArray): Result<Unit> = runCatching { client.sendBytes(bytes) }

    suspend fun disconnect() {
        currentIntent?.requested = true
        // Закрываем сокет ДО ожидания job — иначе блокирующий read() в incoming()
        // будет висеть до таймаута и cancel() не отработает мгновенно.
        runCatching { client.close() }
        connectionJob?.cancelAndJoin()
        connectionJob = null
        _state.value = ConnectionState.Idle
    }

    fun release() {
        currentIntent?.requested = true
        connectionJob?.cancel()
        connectionJob = null
        stopDiscovery()
        internalScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        device?.let { d ->
                            val item = d.toItem(bonded = false)
                            _devices.update { current ->
                                if (current.any { it.address == item.address }) current
                                else current + item
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _scanning.value = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 13+ (API 33) требует явного флага экспорта при регистрации receiver.
        // BT broadcasts — системные (protected), используем RECEIVER_NOT_EXPORTED.
        ContextCompat.registerReceiver(
            appContext,
            r,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = r
    }

    private fun unregisterReceiver() {
        receiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toItem(bonded: Boolean) = BluetoothDeviceItem(
        name = (name ?: "Unknown"),
        address = address,
        bonded = bonded
    )
}
