package com.example.connectapp.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.UsbSerialClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Репозиторий USB serial. По интерфейсу повторяет [WifiRepository] /
 * [BluetoothRepository] — state, incoming, connect, disconnect, send.
 *
 * Особенности USB:
 *   - Нет постоянного "сопряжения" — каждое подключение требует runtime-
 *     разрешения от UsbManager (он показывает системный диалог).
 *   - Подключение/отключение USB-кабеля шлёт системные broadcast'ы
 *     ACTION_USB_DEVICE_ATTACHED/DETACHED — слушаем для авто-disconnect.
 */
class UsbSerialRepository(
    private val appContext: Context,
    private val client: UsbSerialClient = UsbSerialClient()
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val _devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val devices: StateFlow<List<UsbDevice>> = _devices.asStateFlow()

    private var readerJob: Job? = null
    private var detachReceiver: BroadcastReceiver? = null

    fun refreshDevices() {
        _devices.value = client.probe(appContext).map { it.device }
    }

    /**
     * Запрос runtime-разрешения USB. Suspending — возвращается когда юзер
     * нажмёт Allow/Deny в системном диалоге. true если получено.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            if (manager.hasPermission(device)) {
                cont.resume(true); return@suspendCancellableCoroutine
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    runCatching { c.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(
                appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
            )
            manager.requestPermission(device, pi)
            cont.invokeOnCancellation {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }

    /**
     * Подключиться к [device]. Один job на всю связку (permission + connect +
     * read). Раньше внутренний reader-launch присваивался ВНУТРИ внешнего —
     * disconnect между этими двумя launch'ами видел readerJob=null и не
     * отменял ещё не запущенный read-цикл, который потом стартовал и
     * продолжал читать после disconnect.
     */
    fun connect(device: UsbDevice, scope: CoroutineScope, baudRate: Int = 115200) {
        readerJob?.cancel()
        readerJob = scope.launch {
            _state.value = ConnectionState.Connecting
            val granted = runCatching { requestPermission(device) }.getOrDefault(false)
            if (!granted) {
                _state.value = ConnectionState.Error("Нет разрешения на USB-устройство")
                return@launch
            }
            try {
                client.connect(appContext, device, baudRate)
                _state.value = ConnectionState.Connected
                registerDetachReceiver()
                try {
                    client.incoming().collect { _incoming.emit(it) }
                    if (_state.value is ConnectionState.Connected) {
                        _state.value = ConnectionState.Disconnected
                    }
                } catch (e: CancellationException) { throw e }
                catch (t: Throwable) {
                    _state.value = ConnectionState.Error(t.message ?: "USB read error")
                } finally {
                    withContext(NonCancellable) { runCatching { client.close() } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = ConnectionState.Error(t.message ?: "USB connect failed")
                runCatching { client.close() }
            }
        }
    }

    suspend fun send(payload: String): Result<Unit> = runCatching { client.send(payload) }
    suspend fun sendBytes(bytes: ByteArray): Result<Unit> = runCatching { client.sendBytes(bytes) }

    suspend fun disconnect() {
        unregisterDetachReceiver()
        runCatching { client.close() }
        readerJob?.cancelAndJoin()
        readerJob = null
        _state.value = ConnectionState.Idle
    }

    /** Слушает физическое отключение кабеля — переводит в Disconnected. */
    private fun registerDetachReceiver() {
        if (detachReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    // Не пытаемся reconnect — кабель выдернут физически.
                    _state.value = ConnectionState.Disconnected
                    runCatching { client } // noop reference to silence smart-cast
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            r,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        detachReceiver = r
    }

    private fun unregisterDetachReceiver() {
        detachReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        detachReceiver = null
    }

    fun release() {
        unregisterDetachReceiver()
        readerJob?.cancel()
        readerJob = null
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.connectapp.USB_PERMISSION"
    }
}
