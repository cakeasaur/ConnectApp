package com.example.connectapp.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.CommandLog
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.FileReplaySource
import com.example.connectapp.utils.LogBuffer
import com.example.connectapp.utils.MockDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Опциональный файл для replay-режима. Если установлен — start() будет
     * читать из файла, иначе генерит mock-данные через MockDataSource.
     * Устанавливается из Activity при получении intent-extra `replay_file`.
     */
    @Volatile var replayFile: File? = null

    init {
        // В тест-режиме платы нет, но кнопки/консоль на экране графиков шлют
        // команды в CommandBus с target="test". Раньше их никто не слушал —
        // отсюда ощущение «заглушек». Отвечаем синтетически, как реальная плата:
        // ответ уходит в тот же конвейер (лог + консоль), monitor/stop реально
        // запускают/останавливают mock-поток, dump наполняет «Журнал событий».
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                if (cmd.target == "test" || cmd.target == null) respondMock(cmd.payload)
            }
        }
    }

    private fun respondMock(payload: String) {
        val p = payload.lowercase()
        when {
            payload.any { it.code == 27 } -> { stop(); injectFromBoard("Exiting monitoring...\n") }
            "monitor" in p -> { start(); injectFromBoard("Starting enhanced monitoring at 10 Hz\n") }
            "calib" in p ->
                injectFromBoard("Loading calibration data from EEPROM... OK\nCalibration complete\n")
            "test" in p -> injectFromBoard("Self-test: sensors OK, links OK\n")
            "dump" in p -> injectFromBoard(MOCK_RRD_DUMP)
            "help" in p -> injectFromBoard(MOCK_HELP)
            else -> injectFromBoard("ERR: unknown command '${payload.trim()}'\n")
        }
    }

    /** Прогоняет синтетический ответ «платы» через тот же путь, что и реальный поток. */
    private fun injectFromBoard(text: String) {
        logBuffer.appendRaw(text, viewModelScope)
        _lastPacketAt.value = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) { parseChunk(text) }
    }

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
        // Выбор источника: replay-файл или mock-генератор.
        val source: Flow<String> = replayFile?.let { FileReplaySource.stream(it) }
            ?: MockDataSource.stream()
        streamJob = viewModelScope.launch {
            source.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                _lastPacketAt.value = System.currentTimeMillis()
                withContext(Dispatchers.Default) { parseChunk(chunk) }
            }
            // Replay завершился (файл кончился) — переводим в stopped.
            _running.value = false
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _running.value = false
    }

    fun clearLog() {
        logBuffer.clear()
        synchronized(lineBuffer) { lineBuffer.clear() }
        _sensorData.value = SensorData()
        SensorDataBus.clear()
    }

    private fun parseChunk(chunk: String) {
        com.example.connectapp.utils.RrdLog.feedLine(chunk)
        // synchronized — parseChunk бежит на Dispatchers.Default, а clearLog
        // с Main. StringBuilder не thread-safe.
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')
        // Текстовые ответы платы (help/menu/calib/status) приходят без '\n'
        // отдельными чанками — drainRecords (кадрировщик ТЕЛЕМЕТРИИ) их не
        // выдаёт, поэтому консоль их не видела. Фидим CommandLog из сырья: каждая
        // строка чанка, которую парсер НЕ распознал как телеметрию.
        for (raw in normalised.split('\n')) {
            val t = raw.trim()
            if (t.isNotEmpty() && DataParser.parse(t) == null) CommandLog.appendIfText(t)
        }
        val records = synchronized(lineBuffer) {
            lineBuffer.append(normalised)
            DataParser.drainRecords(lineBuffer)
        }
        for (line in records) {
            val parsed = DataParser.parse(line) ?: continue
            val merged = _sensorData.updateAndGet { it.merge(parsed) }
            parsed.temperature1?.let { SensorDataBus.addTemperature(slot = 1, value = it) }
            parsed.temperature2?.let { SensorDataBus.addTemperature(slot = 2, value = it) }
            SensorDataBus.publishAccel(parsed, merged)
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
    }
}

// Синтетический ответ на команду `dump` в тест-режиме — корректный блок RRD,
// который ловит RrdLog и показывает экран «Журнал событий».
private val MOCK_RRD_DUMP =
    "=== RRD Event Log ===\n" +
    "Idx | Date/Time         | Event   | S | Cur | Min | Max | Unit | Dur\n" +
    "----+-------------------+---------+---+-----+-----+-----+------+----\n" +
    "0   | 04.06.26 01:40:00 | A1 Stat | S | 12  | 12  | 12  | LSB  | 0\n" +
    "1   | 04.06.26 01:40:05 | A1 Stat | E | 18  | 12  | 22  | LSB  | 5\n" +
    "2   | 04.06.26 01:41:10 | T1 Warn | S | 29  | 22  | 31  | C    | 0\n" +
    "Total entries: 3\n"

private val MOCK_HELP =
    "Available commands:\n" +
    "  monitor   - start telemetry stream\n" +
    "  calib     - load accelerometer calibration\n" +
    "  test      - run self-test\n" +
    "  log dump  - dump RRD event log\n" +
    "  ESC       - stop monitoring\n"
