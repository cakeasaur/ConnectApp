package com.example.connectapp.data.repository

import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.WifiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WifiRepository(
    private val client: WifiClient = WifiClient()
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var readerJob: Job? = null

    suspend fun connect(host: String, port: Int, scope: CoroutineScope) {
        if (_state.value is ConnectionState.Connected) return
        _state.value = ConnectionState.Connecting
        try {
            client.connect(host, port)
            _state.value = ConnectionState.Connected
            readerJob?.cancel()
            readerJob = scope.launch {
                try {
                    client.incoming().collect { _incoming.emit(it) }
                    // Stream ended cleanly → remote closed.
                    if (_state.value is ConnectionState.Connected) {
                        _state.value = ConnectionState.Disconnected
                    }
                } catch (t: Throwable) {
                    _state.value = ConnectionState.Error(t.message ?: "Read error")
                } finally {
                    runCatching { client.close() }
                }
            }
        } catch (t: Throwable) {
            _state.value = ConnectionState.Error(t.message ?: "Connect failed")
            runCatching { client.close() }
        }
    }

    suspend fun send(payload: String) {
        runCatching { client.send(payload) }
    }

    suspend fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        client.close()
        _state.value = ConnectionState.Idle
    }
}
