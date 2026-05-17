package com.example.connectapp.ui.graph

import androidx.compose.runtime.Stable
import com.example.connectapp.data.models.TimedPoint

/**
 * Шина общего состояния sync-crosshair. Тап на любом чарте обновляет
 * timestamp здесь — все чарты подписаны и одновременно отрисовывают
 * вертикальную линию в этом времени.
 *
 * Объект (не StateFlow) хранится в [androidx.compose.runtime.remember] на
 * уровне GraphScreen — это даёт shared mutable state без боксинга Long.
 *
 * Поддерживает 2 режима:
 *  - SINGLE: только cursor1, тап двигает его
 *  - DUAL: cursor1 + cursor2, тапы заполняют пустой слот, при заполненных
 *    двух — двигают ближайший по timestamp. Bubble показывает Δt/ΔY.
 *
 * @Stable — поля внутри читаются через snapshot из mutableStateOf, но
 *   сам класс не data — Compose без аннотации считает его unstable и
 *   инвалидирует подписчики на каждый recompose родителя.
 */
@Stable
class CrosshairBus {
    /** Первый (основной) cursor. */
    private val s1 = androidx.compose.runtime.mutableStateOf<Long?>(null)
    /** Второй cursor — только в dual-режиме. */
    private val s2 = androidx.compose.runtime.mutableStateOf<Long?>(null)
    /** Режим dual-измерения (Δt, ΔY между cursor1 и cursor2). */
    private val dualState = androidx.compose.runtime.mutableStateOf(false)

    var selectedT: Long?
        get() = s1.value
        set(v) { s1.value = v }
    var secondT: Long?
        get() = s2.value
        set(v) { s2.value = v }
    var dualMode: Boolean
        get() = dualState.value
        set(v) {
            dualState.value = v
            if (!v) s2.value = null  // выход из dual — скрываем 2-й курсор
        }

    /**
     * Обработать тап. В одиночном режиме — просто двигаем cursor1.
     * В dual: первый тап заполняет пустой слот; если оба заняты — двигаем
     * ближайший по timestamp (естественная семантика "поправить курсор").
     */
    fun tap(t: Long) {
        if (!dualState.value) {
            s1.value = t
            return
        }
        when {
            s1.value == null -> s1.value = t
            s2.value == null -> s2.value = t
            else -> {
                val d1 = kotlin.math.abs(s1.value!! - t)
                val d2 = kotlin.math.abs(s2.value!! - t)
                if (d1 < d2) s1.value = t else s2.value = t
            }
        }
    }

    fun clear() { s1.value = null; s2.value = null }
}

/**
 * Поиск ближайшей по timestamp точки в отсортированной по времени серии.
 * Бинарный поиск O(log n) — серии до 600 точек, разница vs линейного не
 * критична, но сохраняем привычку.
 */
internal fun findNearest(series: List<TimedPoint>, t: Long): TimedPoint? {
    if (series.isEmpty()) return null
    if (t <= series.first().t) return series.first()
    if (t >= series.last().t) return series.last()
    var lo = 0; var hi = series.size - 1
    while (lo < hi - 1) {
        val mid = (lo + hi) ushr 1
        if (series[mid].t <= t) lo = mid else hi = mid
    }
    // Возвращаем тот из lo/hi, чей timestamp ближе к t.
    return if (t - series[lo].t <= series[hi].t - t) series[lo] else series[hi]
}
