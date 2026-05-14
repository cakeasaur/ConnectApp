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
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.utils.Constants
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.LogBuffer
import com.example.connectapp.utils.SessionRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BluetoothViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repo = BluetoothRepository(application.applicationContext)
    private val settingsRepo = SettingsRepository(application.applicationContext)
    private val recorder = SessionRecorder(application.applicationContext)

    val state: StateFlow<ConnectionState> = repo.state
    val incoming: SharedFlow<String> = repo.incoming
    val devices: StateFlow<List<BluetoothDeviceItem>> = repo.devices
    val scanning: StateFlow<Boolean> = repo.scanning
    val lastPacketAt: StateFlow<Long?> = repo.lastPacketAt

    val isRecording: StateFlow<Boolean> = recorder.isRecording
    val recordingFile: StateFlow<File?> = recorder.currentFile

    private val logBuffer = LogBuffer(initial = handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = logBuffer.text

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings.DEFAULT)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    var lastAddress: String?
        get() = handle.get<String>(KEY_ADDRESS)
        set(value) { handle[KEY_ADDRESS] = value }

    private val lineBuffer = StringBuilder()
    private var saveLogJob: Job? = null
    private var autoReconnectTried = false

    init {
        // Сохраняем настройки в свойство для синхронного доступа.
        viewModelScope.launch {
            settingsRepo.flow.collect { _settings.value = it }
        }

        viewModelScope.launch {
            repo.incoming.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                scheduleSave()
                recorder.appendChunk(chunk)
                parseChunk(chunk)
            }
        }
        // Forward commands posted from GraphActivity (or any other screen).
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                if (state.value is ConnectionState.Connected) sendSilent(cmd)
            }
        }
        // Auto-monitor: при переходе в Connected (если включено) шлём команду monitor.
        // StateFlow уже эмитит только при изменении значения — не нужен distinctUntilChanged.
        viewModelScope.launch {
            state.collect { s ->
                if (s is ConnectionState.Connected && _settings.value.autoMonitor) {
                    // Маленькая задержка, чтобы устройство успело обработать handshake.
                    delay(300)
                    sendSilent(application.getString(com.example.connectapp.R.string.cmd_monitor))
                }
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

    /** Однократная попытка авто-реконнекта при первом запуске экрана. */
    fun maybeAutoReconnect() {
        if (autoReconnectTried) return
        autoReconnectTried = true
        viewModelScope.launch {
            val s = settingsRepo.flow.first()
            val addr = lastAddress
            if (s.autoReconnect && !addr.isNullOrBlank() && state.value is ConnectionState.Idle) {
                logBuffer.appendLine("[auto] подключаюсь к $addr…", viewModelScope)
                connect(addr)
            }
        }
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

    private fun sendSilent(payload: String) {
        viewModelScope.launch {
            repo.send(payload).onFailure { e ->
                logBuffer.appendLine("← [ERROR] auto: ${e.message ?: "send failed"}", viewModelScope)
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

    fun startRecording(): File? = recorder.start()
    fun stopRecording(): File? = recorder.stop()
    fun shareUriFor(file: File) = recorder.shareUriFor(file)

    fun setAutoReconnect(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoReconnect(value) }
    fun setAutoMonitor(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoMonitor(value) }
    fun setAutoScrollLog(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoScrollLog(value) }

    private fun scheduleSave() {
        saveLogJob?.cancel()
        saveLogJob = viewModelScope.launch {
            delay(Constants.LOG_SAVE_DEBOUNCE_MS)
            logBuffer.flush()
            handle[KEY_LOG] = logBuffer.text.value
        }
    }

    /**
     * Накапливает входящие байты в [lineBuffer], извлекает законченные строки,
     * парсит и публикует значения в [SensorDataBus].
     */
    private fun parseChunk(chunk: String) {
        // Нормализуем переводы строк: некоторые прошивки шлют только '\r'.
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')
        lineBuffer.append(normalised)
        var idx: Int
        while (lineBuffer.indexOf('\n').also { idx = it } >= 0) {
            val line = lineBuffer.substring(0, idx).trim()
            lineBuffer.delete(0, idx + 1)
            if (line.isNotEmpty()) {
                DataParser.parse(line)?.let { parsed ->
                    _sensorData.update { it.merge(parsed) }
                    parsed.temperature1?.let { SensorDataBus.addTemperature(slot = 1, value = it) }
                    parsed.temperature2?.let { SensorDataBus.addTemperature(slot = 2, value = it) }
                    if (parsed.accel1X != null || parsed.accel1Y != null || parsed.accel1Z != null) {
                        SensorDataBus.addAccel(
                            slot = 1,
                            x = parsed.accel1X ?: 0f,
                            y = parsed.accel1Y ?: 0f,
                            z = parsed.accel1Z ?: 0f
                        )
                    }
                    if (parsed.accel2X != null || parsed.accel2Y != null || parsed.accel2Z != null) {
                        SensorDataBus.addAccel(
                            slot = 2,
                            x = parsed.accel2X ?: 0f,
                            y = parsed.accel2Y ?: 0f,
                            z = parsed.accel2Z ?: 0f
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
        recorder.stop()
        repo.release()
    }

    companion object {
        private const val KEY_ADDRESS = "bt.address"
        const val KEY_LOG = "bt.log"
    }
}
