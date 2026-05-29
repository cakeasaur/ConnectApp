package com.example.connectapp.math

import kotlin.math.sqrt

/**
 * Нормированная взаимная корреляция двух временных рядов.
 *
 * Применение в твоей лабе: два акселерометра разнесены физически по балке/
 * корпусу. По смещению пика корреляционной функции узнаём, на сколько
 * отсчётов сигнал B запаздывает от A. Это даёт:
 *   - скорость распространения ударной волны через материал,
 *   - направление, откуда пришёл удар (если знаем геометрию),
 *   - фазовый сдвиг для модального анализа.
 *
 * Формула:
 *   R_xy(τ) = Σ (x[i] - μx)(y[i-τ] - μy) / sqrt(Σ(x-μx)² · Σ(y-μy)²)
 *
 * Диапазон R_xy ∈ [-1, +1]: 1 = идеально совпадают, 0 = независимы.
 */
object CrossCorrelation {

    /**
     * @param x первый сигнал
     * @param y второй сигнал (одной длины с x)
     * @param maxLag максимальный лаг (±) для перебора. Обычно ≤ 10% от длины.
     * @return массив длиной 2·maxLag+1, индекс i соответствует lag = i - maxLag.
     *   Пустой массив, если входы пусты или maxLag некорректен.
     */
    fun normalized(x: FloatArray, y: FloatArray, maxLag: Int): FloatArray {
        val n = minOf(x.size, y.size)
        if (n == 0 || maxLag <= 0 || maxLag >= n) return FloatArray(0)

        // Центрируем по среднему — корреляция должна не зависеть от DC.
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        val mxf = mx.toFloat(); val myf = my.toFloat()

        var sx2 = 0.0; var sy2 = 0.0
        for (i in 0 until n) {
            val a = x[i] - mxf; val b = y[i] - myf
            sx2 += a * a; sy2 += b * b
        }
        val norm = sqrt(sx2 * sy2).coerceAtLeast(1e-12).toFloat()

        val out = FloatArray(2 * maxLag + 1)
        for (lagIdx in 0..2 * maxLag) {
            val lag = lagIdx - maxLag
            // Считаем R_xy(lag) = Σ (x[i] - μx)(y[i - lag] - μy)
            // По диапазону i, в котором оба индекса валидны.
            val from = maxOf(0, lag)
            val to = minOf(n, n + lag)
            var s = 0.0
            for (i in from until to) {
                s += (x[i] - mxf).toDouble() * (y[i - lag] - myf)
            }
            out[lagIdx] = (s / norm).toFloat()
        }
        return out
    }

    /**
     * Индекс лага с максимальной корреляцией.
     * Возвращает (lag в отсчётах, значение корреляции).
     * lag > 0 → сигнал y опережает x на lag отсчётов.
     */
    fun bestLag(corr: FloatArray, maxLag: Int): Pair<Int, Float> {
        if (corr.isEmpty()) return 0 to 0f
        var bi = 0
        for (i in corr.indices) if (corr[i] > corr[bi]) bi = i
        return (bi - maxLag) to corr[bi]
    }
}
