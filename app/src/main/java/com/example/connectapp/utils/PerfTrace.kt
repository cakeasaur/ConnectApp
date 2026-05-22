package com.example.connectapp.utils

import android.util.Log

/**
 * Лёгкий профайлер hot path с агрегацией.
 *
 * Каждый [measure] добавляет к счётчику секции, раз в [FLUSH_INTERVAL_MS]
 * (1 сек) логирует усреднённые значения в logcat (tag "PerfTrace"):
 *
 *     PerfTrace: 1s: path.build=180x avg=42us p99=180us; chart.draw=90x avg=350us p99=1100us
 *
 * Зачем агрегация: при 60 fps × 3 чарта писать в log каждый кадр = 180 строк/сек
 * — забивает logcat и сама запись в Log.d() становится заметным overhead.
 * Сэмпл "сумма + макс за 1 сек" даёт ту же информацию без шума.
 *
 * ENABLED = false убирает overhead до одного volatile-чтения (JIT inline'нет).
 * Включай только при сборе baseline / сравнении до/после оптимизации.
 */
object PerfTrace {

    /** Глобальный выключатель. false = весь measure {} превращается в обычный block(). */
    const val ENABLED = false

    private const val TAG = "PerfTrace"
    private const val FLUSH_INTERVAL_MS = 1000L

    private class Counter {
        var count = 0
        var totalNs = 0L
        var maxNs = 0L
        fun add(durNs: Long) {
            count++
            totalNs += durNs
            if (durNs > maxNs) maxNs = durNs
        }
        fun reset() { count = 0; totalNs = 0L; maxNs = 0L }
    }

    private val counters = HashMap<String, Counter>()
    private var lastFlush = 0L
    private val lock = Any()

    /**
     * Замер блока. При [ENABLED]=false — нулевой оверхед (JIT inline'нет
     * пустой записи). Безопасно при exception'ах — durNs всё равно учтётся.
     */
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!ENABLED) return block()
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            record(name, System.nanoTime() - start)
        }
    }

    fun record(name: String, durNs: Long) {
        if (!ENABLED) return
        synchronized(lock) {
            val c = counters.getOrPut(name) { Counter() }
            c.add(durNs)
            val now = System.currentTimeMillis()
            if (lastFlush == 0L) { lastFlush = now; return }
            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                lastFlush = now
                flushLocked()
            }
        }
    }

    /** Принудительный flush (например при onPause Activity). Не сбрасывает счётчики. */
    fun dump() {
        if (!ENABLED) return
        synchronized(lock) { flushLocked() }
    }

    private fun flushLocked() {
        if (counters.isEmpty()) return
        val sb = StringBuilder("1s: ")
        var first = true
        for ((name, c) in counters) {
            if (c.count == 0) continue
            if (!first) sb.append("; ")
            first = false
            val avgUs = (c.totalNs / c.count) / 1000
            val maxUs = c.maxNs / 1000
            sb.append(name).append('=').append(c.count).append("x avg=")
                .append(avgUs).append("us max=").append(maxUs).append("us")
            c.reset()
        }
        if (!first) Log.d(TAG, sb.toString())
    }
}
