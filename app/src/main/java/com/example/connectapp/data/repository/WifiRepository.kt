package com.example.connectapp.data.repository

import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.WifiClient
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
import kotlinx.coroutines.withContext

class WifiRepository(
    private val client: WifiClient = WifiClient()
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Метка времени последнего пакета. Нужно для UI-индикатора «жив ли поток». */
    private val _lastPacketAt = MutableStateFlow<Long?>(null)
    val lastPacketAt: StateFlow<Long?> = _lastPacketAt.asStateFlow()

    private var readerJob: Job? = null

    suspend fun connect(host: String, port: Int, scope: CoroutineScope) {
        if (_state.value is ConnectionState.Connected) return
        // Дожидаемся завершения предыдущего reader'а ДО открытия нового сокета —
        // иначе старый job в finally закроет client, уже принадлежащий новой сессии.
        readerJob?.cancelAndJoin()
        readerJob = null
        // Гарантируем, что прошлый сокет закрыт (на случай Error/Disconnected без disconnect()).
        runCatching { client.close() }

        _state.value = ConnectionState.Connecting
        try {
            client.connect(host, port)
            _state.value = ConnectionState.Connected
            readerJob = scope.launch {
                try {
                    client.incoming().collect {
                        _lastPacketAt.value = System.currentTimeMillis()
                        _incoming.emit(it)
                    }
                    // Stream ended cleanly → remote closed.
                    if (_state.value is ConnectionState.Connected) {
                        _state.value = ConnectionState.Disconnected
                    }
                } catch (e: CancellationException) {
                    // Manual disconnect — propagate cancellation, no error state.
                    throw e
                } catch (t: Throwable) {
                    _state.value = ConnectionState.Error(t.message ?: "Read error")
                } finally {
                    // NonCancellable guarantees socket close even on cancellation.
                    withContext(NonCancellable) {
                        runCatching { client.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            _state.value = ConnectionState.Error(t.message ?: "Connect failed")
            runCatching { client.close() }
        }
    }

    /** Возвращает Result, чтобы ViewModel могла показать пользователю ошибку отправки. */
    suspend fun send(payload: String): Result<Unit> = runCatching { client.send(payload) }

    suspend fun disconnect() {
        // Закрываем сокет первым — это разблокирует висящий read() в reader'е.
        client.close()
        readerJob?.cancelAndJoin()
        readerJob = null
        _state.value = ConnectionState.Idle
    }
}
