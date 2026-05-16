package com.example.connectapp.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.BluetoothClient
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
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
    private val internalScope = MainScope()

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
    fun bondedDevices(): List<BluetoothDeviceItem> = runCatching {
        // На API 31+ без BLUETOOTH_CONNECT бросает SecurityException.
        // Метод может быть вызван до получения разрешений — возвращаем пустой список.
        adapter?.bondedDevices?.map { it.toItem(bonded = true) } ?: emptyList()
    }.getOrDefault(emptyList())

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
    fun connect(address: String, scope: CoroutineScope) {
        val a = adapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        // Своё намерение для этой сессии — не делим shared boolean со старой,
        // которая могла ещё крутиться в reconnect-петле.
        val intent = DisconnectIntent()
        currentIntent = intent
        stopDiscovery()

        connectionJob?.cancel()
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

                    try {
                        client.connect(a, address)
                        _state.value = ConnectionState.Connected
                        wasConnected = true
                        attempt = 0 // reset counter on successful connect

                        // Blocks until the remote closes or an I/O error occurs.
                        client.incoming().collect {
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
