package com.example.connectapp.ui.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.repository.WifiRepository
import androidx.lifecycle.viewModelScope
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
        viewModelScope.launch { repo.send(payload) }
    }

    fun clearLog() {
        _log.value = ""
        handle[KEY_LOG] = ""
    }

    private fun appendLog(chunk: String) {
        val updated = _log.value + chunk
        _log.value = updated
        handle[KEY_LOG] = updated
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is auto-cancelled here, which triggers readerJob cancellation.
        // Socket cleanup happens in repo.disconnect() via finally block in coroutine.
    }

    companion object {
        private const val KEY_HOST = "wifi.host"
        private const val KEY_PORT = "wifi.port"
        const val KEY_LOG = "wifi.log"
    }
}
