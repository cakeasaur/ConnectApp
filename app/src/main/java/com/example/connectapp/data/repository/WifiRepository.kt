package com.example.connectapp.data.repository

import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.WifiClient
import com.example.connectapp.utils.AlertEngine
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiRepository(
    private val client: WifiClient = WifiClient()
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var readerJob: Job? = null

    /**
     * «Намерение отключиться» — флаг на ТЕКУЩУЮ connect-сессию. Пересоздаётся
     * в [connect], так что новая сессия не видит флаг предыдущей и старая
     * reconnect-петля не тыкает старый адрес после быстрого reconnect.
     * Та же схема, что в [BluetoothRepository].
     */
    private class DisconnectIntent { @Volatile var requested: Boolean = false }
    @Volatile private var currentIntent: DisconnectIntent? = null

    /**
     * Подключается к [host]:[port] и держит связь с авто-реконнектом на
     * неожиданных обрывах (delay [Constants.RECONNECT_DELAY_MS] между
     * попытками). Семантика как в [BluetoothRepository.connect]:
     *   - провал ПЕРВОГО коннекта (ни разу не соединились) → Error, без retry;
     *   - обрыв уже живого соединения → Reconnecting и повтор;
     *   - счётчик попыток сбрасывается только при реально пришедших данных —
     *     иначе «принял и сразу закрыл» крутил бы реконнект вечно; после
     *     [Constants.MAX_RECONNECT_ATTEMPTS] таких пустых обрывов — Error.
     * Реконнект не запускается, если до него вызвали [disconnect].
     */
    suspend fun connect(host: String, port: Int, scope: CoroutineScope) {
        // Идемпотентность: уже соединены / открываемся / реконнектимся — выходим.
        when (_state.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting -> return
            else -> { /* fall through */ }
        }
        // Намерение на эту сессию; глушим возможную старую reconnect-петлю.
        val intent = DisconnectIntent()
        currentIntent?.requested = true
        currentIntent = intent

        // Connecting ДО первой точки приостановки — два быстрых вызова не пройдут
        // гард оба, пока состояние ещё Idle.
        _state.value = ConnectionState.Connecting
        // Дожидаемся прошлого reader'а ДО открытия нового сокета — иначе его
        // finally закроет client уже новой сессии.
        readerJob?.cancelAndJoin()
        readerJob = null
        // Гарантируем, что прошлый сокет закрыт (Error без disconnect()).
        runCatching { client.close() }

        readerJob = scope.launch {
            var attempt = 0
            var wasConnected = false
            try {
                while (isActive && !intent.requested) {
                    if (attempt == 0) {
                        _state.value = ConnectionState.Connecting
                    } else {
                        // Сразу отражаем «переподключение», иначе UI висит в Connected
                        // весь delay() и юзер не понимает, что связь упала.
                        _state.value = ConnectionState.Reconnecting(attempt)
                        delay(Constants.RECONNECT_DELAY_MS)
                    }
                    if (!isActive || intent.requested) break

                    var gotData = false
                    try {
                        client.connect(host, port)
                        AlertEngine.clearEvents()
                        _state.value = ConnectionState.Connected
                        wasConnected = true

                        // Блокируется, пока удалённая сторона не закроет или не
                        // случится I/O-ошибка.
                        client.incoming().collect {
                            // Сброс счётчика только на РЕАЛЬНЫХ данных — иначе
                            // accept-then-close крутил бы реконнект бесконечно.
                            if (!gotData) { gotData = true; attempt = 0 }
                            _incoming.emit(it)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (!wasConnected) {
                            // Первый коннект провалился (неверный адрес/порт) —
                            // ошибка без retry.
                            _state.value = ConnectionState.Error(t.message ?: "Connect failed")
                            return@launch
                        }
                        // Был соединён — ретрай после delay.
                    } finally {
                        withContext(NonCancellable) { runCatching { client.close() } }
                    }

                    if (!intent.requested) attempt++
                    if (!intent.requested && !gotData && attempt >= Constants.MAX_RECONNECT_ATTEMPTS) {
                        _state.value = ConnectionState.Error(
                            "Связь постоянно обрывается — проверьте адрес/порт и что плата шлёт данные"
                        )
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                // Внешняя отмена (manual disconnect / viewModelScope cleared).
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
        // Глушим reconnect-петлю ДО отмены job — иначе finally увидит
        // intent.requested=false и не выставит Idle, а цикл мог бы успеть
        // зайти на новую попытку.
        currentIntent?.requested = true
        // Закрываем сокет первым — это разблокирует висящий read() в reader'е.
        client.close()
        readerJob?.cancelAndJoin()
        readerJob = null
        _state.value = ConnectionState.Idle
    }
}
