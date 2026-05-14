package com.example.connectapp.ui.bluetooth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.repository.BluetoothRepository
import com.example.connectapp.utils.Constants
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.LogBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val logBuffer = LogBuffer(initial = handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = logBuffer.text

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    var lastAddress: String?
        get() = handle.get<String>(KEY_ADDRESS)
        set(value) { handle[KEY_ADDRESS] = value }

    private val lineBuffer = StringBuilder()
    private var saveLogJob: Job? = null

    init {
        viewModelScope.launch {
            repo.incoming.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                scheduleSave()
                parseChunk(chunk)
            }
        }
        // Forward commands posted from GraphActivity (or any other screen).
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                if (state.value is ConnectionState.Connected) sendSilent(cmd)
            }
        }
    }

    fun isAvailable() = repo.isAvailable()
    fun isEnabled() = repo.isEnabled()

    fun loadBonded() {
        repo.stopDiscovery()
        repo.refreshBonded()
    }

    fun startDiscovery() = repo.startDiscovery()
    fun stopDiscovery() = repo.stopDiscovery()

    fun connect(address: String) {
        lastAddress = address
        repo.connect(address, viewModelScope)
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    /** Send a message and echo it to the log (user-initiated). */
    fun send(payload: String) {
        logBuffer.appendLine("→ $payload", viewModelScope)
        scheduleSave()
        viewModelScope.launch {
            repo.send(payload).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    /** Send without logging — used for silent auto-poll requests. */
    private fun sendSilent(payload: String) {
        viewModelScope.launch {
            repo.send(payload).onFailure { e ->
                // Авто-опрос: ошибки помечаем явно, чтобы было ясно что не дошло.
                logBuffer.appendLine("← [ERROR] auto-poll: ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    fun clearLog() {
        saveLogJob?.cancel()
        logBuffer.clear(viewModelScope)
        handle[KEY_LOG] = ""
        lineBuffer.clear()
        _sensorData.value = SensorData()
        SensorDataBus.clear()
    }

    private fun scheduleSave() {
        saveLogJob?.cancel()
        saveLogJob = viewModelScope.launch {
            delay(Constants.LOG_SAVE_DEBOUNCE_MS)
            logBuffer.flush()
            handle[KEY_LOG] = logBuffer.text.value
        }
    }

    /**
     * Accumulates incoming bytes into [lineBuffer], extracts complete lines,
     * parses sensor values and pushes them to [SensorDataBus] for charting.
     */
    private fun parseChunk(chunk: String) {
        // Normalise line endings: some firmware uses "\r\n" or just "\r".
        // Without this, lines never get terminated and parsing never runs.
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')
        lineBuffer.append(normalised)
        var idx: Int
        while (lineBuffer.indexOf('\n').also { idx = it } >= 0) {
            val line = lineBuffer.substring(0, idx).trim()
            lineBuffer.delete(0, idx + 1)
            if (line.isNotEmpty()) {
                DataParser.parse(line)?.let { parsed ->
                    _sensorData.update { it.merge(parsed) }
                    parsed.temperature?.let { SensorDataBus.addTemperature(it) }
                    if (parsed.accelX != null || parsed.accelY != null || parsed.accelZ != null) {
                        SensorDataBus.addAccel(
                            parsed.accelX ?: 0f,
                            parsed.accelY ?: 0f,
                            parsed.accelZ ?: 0f
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveLogJob?.cancel()
        logBuffer.flush()
        handle[KEY_LOG] = logBuffer.text.value
        repo.release()
    }

    companion object {
        private const val KEY_ADDRESS = "bt.address"
        const val KEY_LOG = "bt.log"
    }
}
