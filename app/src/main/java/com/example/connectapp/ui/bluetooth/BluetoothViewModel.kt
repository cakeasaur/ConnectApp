package com.example.connectapp.ui.bluetooth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.repository.BluetoothRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BluetoothViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repo = BluetoothRepository(application.applicationContext)

    val state: StateFlow<ConnectionState> = repo.state
    val incoming: SharedFlow<String> = repo.incoming
    val devices: StateFlow<List<BluetoothDeviceItem>> = repo.devices
    val scanning: StateFlow<Boolean> = repo.scanning

    private val _log = MutableStateFlow(handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = _log.asStateFlow()

    var lastAddress: String?
        get() = handle.get<String>(KEY_ADDRESS)
        set(value) { handle[KEY_ADDRESS] = value }

    init {
        viewModelScope.launch {
            repo.incoming.collect { chunk -> appendLog(chunk) }
        }
    }

    fun isAvailable() = repo.isAvailable()
    fun isEnabled() = repo.isEnabled()

    fun loadBonded() {
        // Render bonded devices immediately, even before discovery starts.
        // We piggy-back on the same StateFlow inside the repo by triggering startDiscovery semantics
        // selectively — but we expose a no-op here that the UI can call to populate.
        repo.stopDiscovery()
        // bondedDevices() is a one-shot pull; emit through a tiny re-init of the flow:
        // Instead of reaching into repo internals, ask the repo to re-publish.
        repo.refreshBonded()
    }

    fun startDiscovery() = repo.startDiscovery()
    fun stopDiscovery() = repo.stopDiscovery()

    fun connect(address: String) {
        lastAddress = address
        viewModelScope.launch { repo.connect(address, viewModelScope) }
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
        repo.release()
        // viewModelScope is auto-cancelled, which cancels readerJob.
        // Socket cleanup happens via finally block in repo coroutines.
    }

    companion object {
        private const val KEY_ADDRESS = "bt.address"
        const val KEY_LOG = "bt.log"
    }
}
