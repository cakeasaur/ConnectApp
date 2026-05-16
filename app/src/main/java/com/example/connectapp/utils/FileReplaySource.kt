package com.example.connectapp.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Reread сохранённой сессии (session_*.txt) с эмуляцией оригинального
 * темпа потока. Использует тот же интерфейс что [MockDataSource] —
 * Flow<String> с кусочками текста.
 *
 * Алгоритм:
 *   - читаем файл целиком в строку (типичный размер 100-500 КБ, не страшно
 *     для RAM; если будут гигабайтные дампы — переписать на BufferedReader)
 *   - режем на чанки [chunkSize] байт и эмитим с задержкой [periodMs]
 *
 * Темп воспроизведения = chunkSize / periodMs. Дефолт ~100 байт / 100мс →
 * условно соответствует ~1 пакету в 100мс (как PIC24 при 10 Hz). Это
 * приблизительно — точные timestamps в session-файле не сохраняются.
 */
object FileReplaySource {

    /**
     * @param file файл с сырыми данными сессии
     * @param periodMs пауза между чанками (мс). Меньше = быстрее воспроизведение.
     * @param chunkSize размер чанка в байтах.
     */
    fun stream(
        file: File,
        periodMs: Long = 100L,
        chunkSize: Int = 100
    ): Flow<String> = flow {
        if (!file.exists() || !file.canRead()) return@flow
        // Читаем весь файл в память — для session-дампов до нескольких МБ это норма.
        // Если файл сильно больше — нужен буферный читатель + skipBytes.
        val text = file.readText(Charsets.UTF_8)
        var pos = 0
        while (pos < text.length) {
            val end = minOf(pos + chunkSize, text.length)
            emit(text.substring(pos, end))
            pos = end
            delay(periodMs)
        }
    }
}
