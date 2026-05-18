package com.example.connectapp.math

import com.example.connectapp.data.models.TimedPoint

/**
 * Численное интегрирование временного ряда акселерометра.
 *
 * Стандартная цепочка для виброанализа:
 *   a (LSB)  →  [integrate]  →  v (LSB·s)  →  [integrate]  →  d (LSB·s²)
 *
 * Drift-коррекция: перед каждым интегрированием применяем [detrend] —
 * вычитаем наклонную прямую через первую и последнюю точку.
 * Это убирает DC-смещение акселерометра (constant bias) и линейный дрейф
 * проинтегрированного сигнала. Без этого скорость уходит в бесконечность
 * за несколько секунд.
 *
 * Метод интегрирования — трапеции: (a[i-1] + a[i]) / 2 * dt.
 * Для 10 Гц и вибрационных сигналов точность более чем достаточная.
 * dt берём из реальных меток времени (не константа), поэтому пропуски
 * пакетов не накапливают ошибку.
 */
object Integration {

    /**
     * Интегрирует [points] методом трапеций.
     * Возвращает список той же длины (первый элемент = 0).
     * Применяет detrend до и после интегрирования.
     */
    fun integrate(points: List<TimedPoint>): List<TimedPoint> {
        if (points.size < 2) return points.map { it.copy(value = 0f) }
        val src = detrend(points)
        val result = ArrayList<TimedPoint>(src.size)
        result.add(TimedPoint(src[0].t, 0f))
        // Double накопитель: при 10 Гц / 10 мин = 6000 шагов Float теряет
        // ~3 значащие цифры из-за катастрофической отмены. Double достаточно.
        var cumulative = 0.0
        for (i in 1 until src.size) {
            val dt = (src[i].t - src[i - 1].t) / 1000.0  // мс → с (Double)
            if (dt <= 0.0) {
                result.add(TimedPoint(src[i].t, cumulative.toFloat()))
                continue
            }
            cumulative += (src[i - 1].value + src[i].value) / 2.0 * dt
            result.add(TimedPoint(src[i].t, cumulative.toFloat()))
        }
        // Второй detrend — убираем дрейф, накопленный из-за оставшегося
        // после первого detrend нелинейного смещения.
        return detrend(result)
    }

    /**
     * Линейное удаление тренда: вычитает прямую через первую и последнюю точки.
     *
     * Лучше простого вычитания среднего: среднее убирает только DC,
     * а линейный тренд убирает ещё и постоянное ускорение/скорость
     * (то что выглядит как наклон на всём окне).
     */
    fun detrend(points: List<TimedPoint>): List<TimedPoint> {
        if (points.size < 2) return points
        val first = points.first()
        val last = points.last()
        val tSpan = (last.t - first.t).toFloat().coerceAtLeast(1f)
        val slope = (last.value - first.value) / tSpan
        return points.map { p ->
            val baseline = first.value + slope * (p.t - first.t)
            p.copy(value = p.value - baseline)
        }
    }
}
