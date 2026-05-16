package com.example.connectapp.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Дескрипторы вибрации по ISO 10816 — оценка состояния машины по сигналу
 * акселерометра. Считаем на скользящем окне, обновляем ~ раз в секунду.
 */
data class VibrationStats(
    /** Среднеквадратичное значение AC-компоненты — общая энергия вибрации. */
    val rms: Float,
    /** Максимум модуля относительно среднего — пиковое значение. */
    val peak: Float,
    /** Crest factor = peak / rms. >4 — деградация подшипника, импульсные удары. */
    val crest: Float,
    /**
     * Эксцесс (4-й нормированный момент). Гауссов шум → 3.0.
     * >3 — наличие ударных импульсов (трещины, сколы, биение).
     */
    val kurtosis: Float
) {
    companion object {
        val EMPTY = VibrationStats(0f, 0f, 0f, 0f)
    }
}

/**
 * Считает [VibrationStats] по массиву отсчётов.
 * Центрирование по среднему — чтобы DC-компонента (например, гравитация по Z)
 * не маскировала AC-вибрацию.
 */
fun computeVibrationStats(values: FloatArray): VibrationStats {
    if (values.isEmpty()) return VibrationStats.EMPTY

    val n = values.size
    var mean = 0.0
    for (v in values) mean += v
    mean /= n

    var sumSq = 0.0   // Σ (x - μ)²
    var sum4 = 0.0    // Σ (x - μ)⁴
    var peak = 0f
    for (v in values) {
        val d = v - mean.toFloat()
        val d2 = d * d
        sumSq += d2
        sum4 += d2 * d2
        val ad = abs(d)
        if (ad > peak) peak = ad
    }

    val variance = sumSq / n
    val rms = sqrt(variance).toFloat()
    val crest = if (rms > 1e-6f) peak / rms else 0f
    // Эксцесс: E[(x-μ)⁴] / σ⁴ — нормированный 4-й центральный момент.
    val kurtosis = if (variance > 1e-12) (sum4 / n / (variance * variance)).toFloat() else 0f

    return VibrationStats(rms, peak, crest, kurtosis)
}
