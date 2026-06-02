package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Текстовый лог ответов платы — НЕ телеметрия, а человекочитаемые строки:
 * меню, help, статусы, ответы калибровки. Заполняется из parseChunk всех
 * транспортов (строки, которые [com.example.connectapp.utils.DataParser]
 * не распознал как данные), читается отдельным экраном «Лог команд».
 *
 * Нужен, чтобы текстовые ответы не тонули в потоке цифр телеметрии.
 */
object CommandLog {

    private const val MAX_CHARS = 20_000

    private val lock = Any()
    private val sb = StringBuilder()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    fun append(line: String) {
        synchronized(lock) {
            sb.append(line).append('\n')
            if (sb.length > MAX_CHARS) sb.delete(0, sb.length - MAX_CHARS)
            _text.value = sb.toString()
        }
    }

    /**
     * Добавляет строку, только если она похожа на текстовый ответ платы, а не
     * на телеметрию. Телеметрия — это цифры и разделители `;,.-`; текст имеет
     * буквы или символы рисования таблиц `| + =` (заголовки/разделители RRD
     * Event Log), которых в телеметрии не бывает.
     */
    fun appendIfText(line: String) {
        if (line.any { it.isLetter() || it == '|' || it == '+' || it == '=' }) append(line)
    }

    fun clear() {
        synchronized(lock) {
            sb.setLength(0)
            _text.value = ""
        }
    }
}
