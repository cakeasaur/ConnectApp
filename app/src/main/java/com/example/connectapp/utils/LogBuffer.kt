package com.example.connectapp.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Накопительный лог с фиксированным окном. Раньше appendLog был O(n) на каждое
 * сообщение (`_log.value + chunk` → `takeLast(MAX)` создавали две строки),
 * что при monitor-режиме ощутимо лагало UI.
 *
 * Здесь:
 *  - StringBuilder хранит все байты линейно;
 *  - при превышении лимита одной операцией `delete(0, len - max)` (один alloc вместо двух);
 *  - публикация в [text] дебаунсится через [PUBLISH_DEBOUNCE_MS], чтобы не дёргать
 *    Compose-реcomp на каждый чанк потока сенсоров.
 *
 * Также поддерживает префикс "HH:mm:ss.SSS" к каждой строке (через [appendLine]).
 */
class LogBuffer(
    private val maxChars: Int = Constants.MAX_LOG_CHARS,
    initial: String = ""
) {
    private val sb = StringBuilder(maxChars + 1024).apply { append(initial) }
    private val _text = MutableStateFlow(initial)
    val text: StateFlow<String> = _text.asStateFlow()

    // Защищает check-then-launch в schedulePublish. Раньше там был
    // `if (publishJob?.isActive == true) return` — две корутины из разных
    // потоков могли проскочить проверку одновременно и стартовать два job'а.
    private val publishLock = Any()
    private var publishJob: Job? = null

    /** Сырая запись чанка (как пришёл с устройства, без префиксов). */
    fun appendRaw(chunk: String, scope: CoroutineScope) {
        synchronized(sb) {
            sb.append(chunk)
            trim()
        }
        schedulePublish(scope)
    }

    /** Запись «строки» с префиксом времени и переводом строки. */
    fun appendLine(line: String, scope: CoroutineScope) {
        // DateTimeFormatter — immutable и thread-safe, в отличие от SimpleDateFormat.
        // Время форматируем ВНЕ synchronized(sb), чтобы не держать lock на forматировании.
        val prefix = LocalDateTime.now().format(TS_FORMATTER)
        synchronized(sb) {
            sb.append('[').append(prefix).append("] ").append(line)
            if (!line.endsWith('\n')) sb.append('\n')
            trim()
        }
        schedulePublish(scope)
    }

    fun clear() {
        synchronized(sb) { sb.setLength(0) }
        synchronized(publishLock) {
            publishJob?.cancel()
            publishJob = null
        }
        _text.value = ""
    }

    /** Принудительная публикация (например, перед сохранением в SavedStateHandle). */
    fun flush() {
        synchronized(publishLock) {
            publishJob?.cancel()
            publishJob = null
        }
        _text.value = synchronized(sb) { sb.toString() }
    }

    private fun trim() {
        val over = sb.length - maxChars
        if (over > 0) sb.delete(0, over)
    }

    private fun schedulePublish(scope: CoroutineScope) {
        synchronized(publishLock) {
            if (publishJob?.isActive == true) return
            publishJob = scope.launch {
                delay(PUBLISH_DEBOUNCE_MS)
                _text.value = synchronized(sb) { sb.toString() }
            }
        }
    }

    companion object {
        private const val PUBLISH_DEBOUNCE_MS = 80L
        private val TS_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
