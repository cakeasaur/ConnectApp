package com.example.connectapp.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.connectapp.data.models.TimedPoint

/**
 * Возвращает [value] если [paused] = false; иначе — значение, захваченное
 * в момент, когда [paused] стало true. Используется для "заморозки" графиков
 * на текущем кадре, пока поток данных продолжает приходить в SensorDataBus.
 *
 * Реализация: внутренний `mutableStateOf` хранит снимок, [LaunchedEffect]
 * обновляет его на rising-edge paused. Не задерживает recompose — рендер
 * просто читает из захваченного снимка.
 */
@Composable
fun <T> snapshotWhen(paused: Boolean, value: T): T {
    val frozen = remember { mutableStateOf(value) }
    LaunchedEffect(paused) {
        if (paused) frozen.value = value
    }
    return if (paused) frozen.value else value
}

/**
 * Человеко-читаемый формат окна: "5s" / "1m 30s" / "12m" / "1h 5m" / "все".
 */
fun formatWindow(windowMs: Long): String {
    if (windowMs <= 0L) return "все"
    val totalSec = windowMs / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
        else -> "${s}s"
    }
}

/**
 * Возвращает суффикс серии, попадающий в окно длиной [windowMs] относительно
 * последней точки. Если [windowMs] ≤ 0 — возвращает [series] как есть.
 *
 * Алгоритм: считаем cutoff = lastT - windowMs, ищем первый индекс с t ≥ cutoff
 * линейно с конца (timestamps монотонно возрастают, поэтому это O(window_pts)
 * в худшем случае — обычно <100 для 30с окна при 10 Hz).
 */
fun applyWindow(series: List<TimedPoint>, windowMs: Long): List<TimedPoint> {
    if (windowMs <= 0L || series.isEmpty()) return series
    val cutoff = series.last().t - windowMs
    // Линейный проход с конца — короче чем binarySearch для small window.
    var from = series.size - 1
    while (from > 0 && series[from - 1].t >= cutoff) from--
    return if (from == 0) series else series.subList(from, series.size)
}
