package com.example.connectapp.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Авто-распознавание команд платы из текстового вывода `help`.
 *
 * Универсальность: вместо хардкода кнопок monitor/calib/test приложение
 * парсит реальный help конкретной прошивки и предлагает её команды чипами.
 *
 * Эвристика. Захват включается строкой-заголовком (содержит «command»/«help»
 * или кончается двоеточием) и держится [CAPTURE_LINES] строк. Команда — это
 * строка вида `имя [<args>] - описание`. Имя берётся слева от ` - `, до первого
 * `<` (плейсхолдера аргумента). Так из «set cpu clock <value> - …» берётся
 * «set cpu clock», из «monitor   - …» — «monitor». Пустой список = help не
 * распознан (тогда UI просто не показывает чипы).
 */
object HelpCommands {

    private const val MAX = 40
    private const val CAPTURE_LINES = 40

    // имя (буквы/цифры/пробел/_), затем опц. <args>, затем " - " и непустое описание.
    private val cmdRegex =
        Regex("""^\s*([A-Za-z][A-Za-z0-9 _]{0,23}?)(?:\s+<[^>]*>)*\s+-\s+\S""")

    private val lock = Any()
    private val set = LinkedHashSet<String>()
    private var capture = 0

    private val _commands = MutableStateFlow<List<String>>(emptyList())
    val commands: StateFlow<List<String>> = _commands.asStateFlow()

    /** Скармливает строку текстового вывода платы (не телеметрию). */
    fun feed(line: String) {
        val t = stripAnsi(line).trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            val low = t.lowercase()
            if (low.contains("command") || low.contains("help") || t.endsWith(":")) {
                capture = CAPTURE_LINES
            }
            if (capture <= 0) return
            capture--

            val m = cmdRegex.find(t) ?: return
            val cmd = m.groupValues[1].trim()
            if (cmd.isEmpty() || cmd.length > 24) return
            // Заголовки-описания вида "Available commands" не считаем командой.
            if (cmd.equals("available", true) || cmd.equals("type", true)) return
            if (set.add(cmd)) {
                while (set.size > MAX) set.iterator().run { next(); remove() }
                _commands.value = set.toList()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            set.clear()
            capture = 0
            _commands.value = emptyList()
        }
    }
}
