package com.example.connectapp.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Одна запись журнала событий RRD платы. Числовые поля держим строками —
 *  формат реальной платы пока зафиксирован одним примером, не теряем сырьё. */
data class RrdEvent(
    val idx: Int,
    val dateTime: String,
    val event: String,
    val marker: String,   // S (start) / E (end)
    val cur: String,
    val min: String,
    val max: String,
    val unit: String,
    val dur: String,
)

/** Распарсенный дамп `=== RRD Event Log ===`. */
data class RrdDump(
    val events: List<RrdEvent>,
    val totalEntries: Int?,
    /** Когда дамп снят (часы устройства, epoch ms). 0 = не штампован. */
    val capturedAt: Long = 0L,
)

/**
 * Парсер блока `=== RRD Event Log ===` из вывода команды `log dump`.
 *
 * Толерантен к формату: колонки определяются по строке-заголовку (`Idx |
 * Date/Time | Event | ...`), а не по фиксированной ширине, поэтому переживает
 * перестановку/добавление колонок. ANSI-коды эхо-консоли вычищаются заранее.
 *
 * Возвращает null, если блок не найден.
 */
object RrdEventLogParser {

    private val totalRegex = Regex("""(?i)total\s+entries\s*:\s*(\d+)""")

    fun parse(raw: String): RrdDump? {
        val lines = stripAnsi(raw).lines()

        // Заголовок таблицы — строка с '|' и колонкой Idx.
        val headerIdx = lines.indexOfFirst { it.contains('|') && it.contains("Idx", ignoreCase = true) }
        if (headerIdx < 0) return null
        val cols = splitCells(lines[headerIdx])

        fun colContains(name: String) = cols.indexOfFirst { it.contains(name, ignoreCase = true) }
        val iIdx = colContains("Idx")
        val iDate = colContains("Date")
        val iEvent = colContains("Event")
        val iMarker = cols.indexOfFirst { it.equals("S", ignoreCase = true) }
        val iCur = colContains("Cur")
        val iMin = colContains("Min")
        val iMax = colContains("Max")
        val iUnit = colContains("Unit")
        val iDur = colContains("Dur")

        val events = ArrayList<RrdEvent>()
        var total: Int? = null

        for (i in (headerIdx + 1) until lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            totalRegex.find(t)?.let { total = it.groupValues[1].toIntOrNull() }
            // Конец блока: "Total entries:" или промпт.
            if (t.startsWith("Total", ignoreCase = true) || t.startsWith(">")) break
            // Строки-разделители из дефисов/плюсов/пайпов.
            if (t.all { it == '-' || it == '+' || it == '|' || it == ' ' }) continue

            val cells = splitCells(lines[i])
            val idx = cells.getOrNull(iIdx)?.toIntOrNull() ?: continue
            fun cell(p: Int) = if (p >= 0 && p < cells.size) cells[p] else ""
            events.add(
                RrdEvent(
                    idx = idx,
                    dateTime = cell(iDate),
                    event = cell(iEvent),
                    marker = cell(iMarker),
                    cur = cell(iCur),
                    min = cell(iMin),
                    max = cell(iMax),
                    unit = cell(iUnit),
                    dur = cell(iDur),
                )
            )
        }

        if (events.isEmpty() && total == null) return null
        return RrdDump(events, total)
    }

    private fun splitCells(line: String): List<String> =
        line.split('|').map { it.trim() }
}

/**
 * Хранилище последнего распарсенного RRD-дампа. Авто-детект: [feedLine]
 * получает СЫРЫЕ чанки потока (плата шлёт каждую строку дампа отдельным
 * чанком без `\n`), ловит блок от «RRD Event Log» до «Total entries»,
 * парсит и публикует в [dump]. Экран наблюдает [dump].
 */
object RrdLog {

    private const val MAX_BUF = 16_000

    private val lock = Any()
    private val buf = StringBuilder()
    private var capturing = false

    private val _dump = MutableStateFlow<RrdDump?>(null)
    val dump: StateFlow<RrdDump?> = _dump.asStateFlow()

    /**
     * Скармливает СЫРОЙ чанк потока (плата шлёт каждую строку дампа отдельным
     * чанком без `\n`, поэтому line-framing их не выделяет — берём сырьё).
     */
    fun feedLine(line: String) {
        synchronized(lock) {
            val clean = stripAnsi(line)
            val t = clean.trim()
            if (t.contains("RRD Event Log", ignoreCase = true)) {
                capturing = true
                buf.setLength(0)
            }
            if (!capturing) return
            buf.append(clean).append('\n')
            when {
                t.contains("Total entries", ignoreCase = true) -> {
                    capturing = false
                    val parsed = RrdEventLogParser.parse(buf.toString())
                    parsed?.let { _dump.value = it.copy(capturedAt = System.currentTimeMillis()) }
                    buf.setLength(0)
                }
                buf.length > MAX_BUF -> {        // незавершённый дамп — сброс
                    capturing = false
                    buf.setLength(0)
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            capturing = false
            buf.setLength(0)
            _dump.value = null
        }
    }
}
