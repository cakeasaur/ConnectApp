package com.example.connectapp.ui.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.repository.WifiRepository
import com.example.connectapp.utils.Constants
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WifiViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repo = WifiRepository()

    val state: StateFlow<ConnectionState> = repo.state
    val incoming: SharedFlow<String> = repo.incoming

    /** Accumulated log, persisted across configuration changes. */
    private val _log = MutableStateFlow(handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = _log.asStateFlow()

    private var saveLogJob: Job? = null

    var host: String
        get() = handle.get<String>(KEY_HOST).orEmpty()
        set(value) { handle[KEY_HOST] = value }

    var port: String
        get() = handle.get<String>(KEY_PORT).orEmpty()
        set(value) { handle[KEY_PORT] = value }

    init {
        viewModelScope.launch {
            repo.incoming.collect { chunk -> appendLog(chunk) }
        }
    }

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port.toString()
        viewModelScope.launch {
            repo.connect(host, port, viewModelScope)
        }
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    fun send(payload: String) {
        // Echo outgoing message to log so user can see what they sent.
        appendLog("→ $payload\n")
        viewModelScope.launch {
            repo.send(payload).onFailure { e ->
                appendLog("← [ERROR] ${e.message ?: "send failed"}\n")
            }
        }
    }

    fun clearLog() {
        saveLogJob?.cancel()
        _log.value = ""
        handle[KEY_LOG] = ""
    }

    private fun appendLog(chunk: String) {
        // Ограничиваем размер лога — иначе OOM при длительной работе.
        val raw = _log.value + chunk
        val updated = if (raw.length > Constants.MAX_LOG_CHARS) {
            raw.takeLast(Constants.MAX_LOG_CHARS)
        } else {
            raw
        }
        _log.value = updated

        // Debounce записи в SavedStateHandle — сериализация дорогая.
        saveLogJob?.cancel()
        saveLogJob = viewModelScope.launch {
            delay(Constants.LOG_SAVE_DEBOUNCE_MS)
            handle[KEY_LOG] = _log.value
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveLogJob?.cancel()
        // Финальная синхронная запись лога перед уничтожением VM.
        handle[KEY_LOG] = _log.value
        // viewModelScope is auto-cancelled here, which triggers readerJob cancellation.
        // Socket cleanup happens in repo.disconnect() via finally block in coroutine.
    }

    companion object {
        private const val KEY_HOST = "wifi.host"
        private const val KEY_PORT = "wifi.port"
        const val KEY_LOG = "wifi.log"
    }
}
