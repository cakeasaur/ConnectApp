package com.example.connectapp.ui.usb

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.repository.UsbSerialRepository
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.LineEnding
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.service.ConnectionForegroundService
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VM USB serial экрана. Минимальный набор vs BluetoothVM:
 *  - нет scan/discovery (USB шлёт broadcast при attach автоматически)
 *  - нет истории подключений (USB-устройство — VID:PID, не MAC)
 *  - нет калибровки/quick-команд (используй BT для устройств с
 *    привычной командной схемой; USB-экран — для базового мониторинга)
 *
 * Парсит входящий поток в SensorDataBus, поэтому Графики и Математика
 * работают одинаково и для BT, и для USB.
 */
class UsbSerialViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = UsbSerialRepository(application.applicationContext)
    private val settingsRepo = SettingsRepository(application.applicationContext)

    val state: StateFlow<ConnectionState> = repo.state
    val devices: StateFlow<List<UsbDevice>> = repo.devices

    private val logBuffer = LogBuffer()
    val log: StateFlow<String> = logBuffer.text

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings.DEFAULT)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val lineBuffer = StringBuilder()

    init {
        viewModelScope.launch {
            settingsRepo.flow.collect { _settings.value = it }
        }
        viewModelScope.launch {
            repo.incoming.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                withContext(Dispatchers.Default) { parseChunk(chunk) }
            }
        }
        // Foreground service — то же что у BT/Wi-Fi, чтобы Android не убил
        // процесс при сворачивании во время записи длинной сессии.
        viewModelScope.launch {
            var wasConnected = false
            state.collect { s ->
                val nowConnected = s is ConnectionState.Connected
                val transition = nowConnected && !wasConnected
                wasConnected = nowConnected
                if (transition) {
                    ConnectionForegroundService.start(
                        application,
                        application.getString(
                            com.example.connectapp.R.string.fg_title_usb,
                            "USB"
                        )
                    )
                } else if (!nowConnected) {
                    ConnectionForegroundService.stop(application)
                }
            }
        }
    }

    fun refreshDevices() = repo.refreshDevices()

    fun connect(device: UsbDevice) {
        SensorDataBus.clear()
        _sensorData.value = SensorData()
        repo.connect(device, viewModelScope)
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    fun send(payload: String) {
        logBuffer.appendLine("→ $payload", viewModelScope)
        val terminated = payload + _settings.value.lineEnding.suffix
        viewModelScope.launch {
            repo.send(terminated).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
            }
        }
    }

    fun clearLog() {
        logBuffer.clear(viewModelScope)
        lineBuffer.clear()
        _sensorData.value = SensorData()
        SensorDataBus.clear()
    }

    fun setLineEnding(value: LineEnding) =
        viewModelScope.launch { settingsRepo.setLineEnding(value) }

    private fun parseChunk(chunk: String) {
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')
        lineBuffer.append(normalised)
        var idx: Int
        while (lineBuffer.indexOf('\n').also { idx = it } >= 0) {
            val line = lineBuffer.substring(0, idx).trim()
            lineBuffer.delete(0, idx + 1)
            if (line.isEmpty()) continue
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

    override fun onCleared() {
        super.onCleared()
        repo.release()
        ConnectionForegroundService.stop(getApplication())
    }
}
