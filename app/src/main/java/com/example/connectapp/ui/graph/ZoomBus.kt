package com.example.connectapp.ui.graph

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf

/**
 * Общее состояние pinch-zoom + pan для NeonChart.
 *
 * Один экземпляр на GraphScreen (remember) передаётся всем трём чартам —
 * зум и прокрутка синхронизированы: если приблизить акселерометр, температура
 * тоже приблизится до того же временного окна.
 *
 * Модель:
 *  - [scaleX]         — коэффициент приближения (1.0 = весь диапазон, 10.0 = 1/10).
 *  - [offsetFraction] — положение окна в диапазоне 0..1:
 *                         0.0 = окно у начала данных,
 *                         1.0 = окно у конца (следит за живыми данными).
 *
 * [applyTo] вычисляет реальные firstT/lastT для рендера.
 * [reset]   сбрасывает зум (возврат к live-режиму).
 */
@Stable
class ZoomBus {

    private val _scale = mutableFloatStateOf(1f)
    private val _offset = mutableFloatStateOf(1f)

    var scaleX: Float
        get() = _scale.floatValue
        set(v) { _scale.floatValue = v.coerceIn(1f, MAX_SCALE) }

    /** 0.0 = привязан к началу данных, 1.0 = следит за последними данными. */
    var offsetFraction: Float
        get() = _offset.floatValue
        set(v) { _offset.floatValue = v.coerceIn(0f, 1f) }

    val isZoomed: Boolean get() = scaleX > 1.02f

    fun reset() {
        _scale.floatValue = 1f
        _offset.floatValue = 1f
    }

    /**
     * Возвращает (firstT, lastT) видимого окна с учётом текущего зума.
     * При [isZoomed] == false — возвращает исходный диапазон без изменений.
     *
     * Math:
     *   zoomedRange = rawRange / scaleX
     *   maxOffset   = rawRange - zoomedRange  (на сколько можно сдвинуть окно)
     *   windowStart = rawFirstT + maxOffset * offsetFraction
     */
    fun applyTo(rawFirstT: Long, rawLastT: Long): Pair<Long, Long> {
        if (!isZoomed || rawFirstT >= rawLastT) return rawFirstT to rawLastT
        val rawRange = rawLastT - rawFirstT
        // Math в Double: rawFirstT — current timestamp ms (~1.7e12), Float
        // ULP при таком значении ~131000 ms (больше типичного окна зума).
        // Если делать (rawFirstT + Float).toLong() — rawFirstT теряет точность
        // при конверсии Long→Float, pan-жест перестаёт двигать окно.
        // Считаем offset отдельно как Long и складываем с rawFirstT (Long+Long — exact).
        val zoomedRange = (rawRange / scaleX.toDouble()).toLong().coerceAtLeast(500L)
        val maxOffset = (rawRange - zoomedRange).coerceAtLeast(0L)
        val offsetMs = (maxOffset * offsetFraction.toDouble()).toLong()
            .coerceIn(0L, maxOffset)
        val windowStart = rawFirstT + offsetMs
        return windowStart to (windowStart + zoomedRange)
    }

    companion object {
        const val MAX_SCALE = 50f
    }
}
