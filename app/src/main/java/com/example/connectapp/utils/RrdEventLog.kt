package com.example.connectapp.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
    /** Сырьё блока ровно как пришло с платы (для режима «Сырьё» и реэкспорта). */
    val rawText: String = "",
)

/**
 * Событие, схлопнутое из пары S(start)/E(end) одного имени в один интервал.
 * [durationSec] считается по меткам времени; [durRaw] — поле Dur платы как есть.
 */
data class RrdInterval(
    val event: String,
    val startIdx: Int,
    val endIdx: Int?,
    val startTime: String,
    val endTime: String?,
    val durationSec: Long?,
    val durRaw: String,
    val cur: String,
    val min: String,
    val max: String,
    val unit: String,
    val ongoing: Boolean,
)

private val rrdDateFmt = java.text.SimpleDateFormat("dd.MM.yy HH:mm:ss", java.util.Locale.ROOT)

private fun rrdDurationSec(start: String, end: String?): Long? {
    if (end.isNullOrBlank() || start.isBlank()) return null
    return try {
        val s = rrdDateFmt.parse(start) ?: return null
        val e = rrdDateFmt.parse(end) ?: return null
        (e.time - s.time) / 1000
    } catch (_: Exception) {
        null
    }
}

private fun intervalOf(start: RrdEvent, end: RrdEvent?): RrdInterval {
    val measure = end ?: start
    return RrdInterval(
        event = start.event,
        startIdx = start.idx,
        endIdx = end?.idx,
        startTime = start.dateTime,
        endTime = end?.dateTime,
        durationSec = rrdDurationSec(start.dateTime, end?.dateTime),
        durRaw = measure.dur,
        cur = measure.cur, min = measure.min, max = measure.max, unit = measure.unit,
        ongoing = end == null && start.marker.equals("S", ignoreCase = true),
    )
}

/**
 * Схлопывает плоский список событий в интервалы: пара S+E одного имени → один
 * интервал с длительностью. Непарный S → ongoing-интервал; одинокий E или
 * прочий маркер — как самостоятельная запись. Порядок — по стартовому idx.
 */
fun pairRrdEvents(events: List<RrdEvent>): List<RrdInterval> {
    val out = ArrayList<RrdInterval>()
    val openByEvent = LinkedHashMap<String, RrdEvent>()
    for (e in events) {
        when {
            e.marker.equals("S", ignoreCase = true) -> {
                openByEvent.remove(e.event)?.let { out.add(intervalOf(it, null)) }
                openByEvent[e.event] = e
            }
            e.marker.equals("E", ignoreCase = true) -> {
                val s = openByEvent.remove(e.event)
                out.add(if (s != null) intervalOf(s, e) else intervalOf(e, null))
            }
            else -> out.add(intervalOf(e, null))
        }
    }
    for (s in openByEvent.values) out.add(intervalOf(s, null))
    out.sortBy { it.startIdx }
    return out
}

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
    private var persistFile: File? = null

    private val _dump = MutableStateFlow<RrdDump?>(null)
    val dump: StateFlow<RrdDump?> = _dump.asStateFlow()

    /**
     * Итог последнего ответа платы на `log dump` — человекочитаемая фраза для
     * экранного фидбэка (Snackbar/Toast). Эмитим при пустом журнале и при
     * успешном парсинге таблицы; экран сам решает, как показать.
     */
    data class Result(val message: String, val count: Int)
    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    /**
     * Привязывает файл персиста и восстанавливает последний дамп с диска, чтобы
     * журнал пережил перезапуск процесса. Вызывать один раз из Application.
     */
    fun init(file: File) {
        synchronized(lock) {
            persistFile = file
            if (_dump.value == null) loadFromDisk(file)
        }
    }

    /**
     * Скармливает СЫРОЙ чанк потока (плата шлёт каждую строку дампа отдельным
     * чанком без `\n`, поэтому line-framing их не выделяет — берём сырьё).
     */
    fun feedLine(line: String) {
        synchronized(lock) {
            val clean = stripAnsi(line)
            val t = clean.trim()
            // Пустой журнал: плата (напр. BOLUTEK/PIC) на `log dump` отвечает
            // одной строкой «No events in log.» — таблицы и «Total entries» нет,
            // поэтому обычный захват не срабатывает. Ловим явно.
            if (t.contains("No events in log", ignoreCase = true)) {
                _dump.value = RrdDump(emptyList(), 0, capturedAt = System.currentTimeMillis(), rawText = t)
                _results.tryEmit(Result("Журнал платы пуст — событий нет", 0))
                capturing = false
                buf.setLength(0)
                return
            }
            if (t.contains("RRD Event Log", ignoreCase = true)) {
                capturing = true
                buf.setLength(0)
            }
            if (!capturing) return
            buf.append(clean).append('\n')
            when {
                t.contains("Total entries", ignoreCase = true) -> {
                    capturing = false
                    val raw = buf.toString()
                    RrdEventLogParser.parse(raw)?.let {
                        val d = it.copy(capturedAt = System.currentTimeMillis(), rawText = raw)
                        _dump.value = d
                        saveToDisk(d)
                        _results.tryEmit(Result("Журнал получен: ${d.events.size} записей", d.events.size))
                    }
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
            persistFile?.let { runCatching { it.delete() } }
        }
    }

    /** Формат файла: первая строка — capturedAt (epoch ms), остальное — сырьё. */
    private fun saveToDisk(d: RrdDump) {
        val f = persistFile ?: return
        runCatching { f.writeText("${d.capturedAt}\n${d.rawText}") }
    }

    private fun loadFromDisk(f: File) {
        if (!f.exists()) return
        runCatching {
            val content = f.readText()
            val nl = content.indexOf('\n')
            if (nl < 0) return@runCatching
            val capturedAt = content.substring(0, nl).trim().toLongOrNull() ?: 0L
            val raw = content.substring(nl + 1)
            RrdEventLogParser.parse(raw)?.let {
                _dump.value = it.copy(capturedAt = capturedAt, rawText = raw)
            }
        }
    }
}
