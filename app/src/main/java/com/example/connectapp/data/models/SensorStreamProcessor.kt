package com.example.connectapp.data.models

import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.RrdLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Общий конвейер разбора входящего потока платы для всех транспортов
 * (Bluetooth / USB / Test). Раньше этот код был скопирован в трёх ViewModel
 * почти дословно, и любая правка требовала синхронных изменений в трёх местах.
 *
 * Делает три вещи на каждый чанк:
 *   1. Скармливает сырьё [RrdLog] для авто-детекта блока `=== RRD Event Log ===`.
 *   2. Текстовые ответы платы (help/menu/calib/status — без `\n`, отдельными
 *      чанками) кладёт в [CommandLog]. drainRecords их не кадрирует (он для
 *      телеметрии), поэтому фидим напрямую из сырья, исключая распознанную
 *      телеметрию.
 *   3. Телеметрию кадрирует через [DataParser.drainRecords] и публикует в
 *      [SensorDataBus] + накапливает текущий снимок [sensorData].
 *
 * Буфер строк под `synchronized`: [process] обычно бежит на Dispatchers.Default,
 * а [reset] вызывается с Main.
 */
class SensorStreamProcessor {

    private val lineBuffer = StringBuilder()
    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    fun process(chunk: String) {
        RrdLog.feedLine(chunk)
        // Нормализуем переводы строк: некоторые прошивки шлют только '\r'.
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')

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

    /** Сбрасывает буфер строк и текущий снимок — при connect/clear новой сессии. */
    fun reset() {
        synchronized(lineBuffer) { lineBuffer.setLength(0) }
        _sensorData.value = SensorData()
    }
}
