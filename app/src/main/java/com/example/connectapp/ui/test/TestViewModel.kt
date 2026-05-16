package com.example.connectapp.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.LogBuffer
import com.example.connectapp.utils.MockDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VM для тестового режима — те же модели данных что и Bluetooth, но источник —
 * MockDataSource. Это даёт возможность отлаживать парсер/графики/CSV без платы.
 */
class TestViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val logBuffer = LogBuffer()
    val log: StateFlow<String> = logBuffer.text

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastPacketAt = MutableStateFlow<Long?>(null)
    val lastPacketAt: StateFlow<Long?> = _lastPacketAt.asStateFlow()

    private val lineBuffer = StringBuilder()
    private var streamJob: Job? = null

    fun toggle() {
        if (_running.value) stop() else start()
    }

    fun start() {
        if (_running.value) return
        // Чистим bus и локальный буфер — иначе при повторном старте mock
        // прилепится к данным прошлой сессии (или к данным BT, если кто-то
        // случайно запустил оба источника).
        SensorDataBus.clear()
        lineBuffer.clear()
        _sensorData.value = SensorData()
        _running.value = true
        streamJob = viewModelScope.launch {
            MockDataSource.stream().collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                _lastPacketAt.value = System.currentTimeMillis()
                withContext(Dispatchers.Default) { parseChunk(chunk) }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _running.value = false
    }

    fun clearLog() {
        logBuffer.clear(viewModelScope)
        lineBuffer.clear()
        _sensorData.value = SensorData()
        SensorDataBus.clear()
    }

    private fun parseChunk(chunk: String) {
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
        streamJob?.cancel()
    }
}
