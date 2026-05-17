package com.example.connectapp.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * FFT по алгоритму Cooley-Tukey radix-2 in-place.
 *
 * Использование: [amplitudeSpectrum] возвращает одностороннюю амплитудную
 * спектральную плотность длиной N/2 (бины 0..fs/2). Перед FFT накладывается
 * окно Ханна для подавления спектральной утечки.
 *
 * Зачем своя реализация: kotlinx-math FFT нет в Android, JTransforms тянет
 * 200 КБ JAR. Cooley-Tukey radix-2 — 60 строк, идеально для нашего N≤1024.
 *
 * Производительность: 1024-point FFT на Float — ~2 мс на Snapdragon 7xx.
 * Окно строится при каждом вызове (минор-overhead, можно закэшировать).
 */
object Fft {

    /**
     * Амплитудный спектр сигнала с компенсацией Hann coherent gain.
     *
     * @param input входные real-значения. Если длина не степень 2 — обрежется
     *   вниз до ближайшей (пример: 250 → 128, 600 → 512).
     * @return массив длиной N/2, в индексе k — амплитуда частоты k * fs / N.
     *
     * Нормализация: `4/N * |X[k]|`. Множитель 4/N разбит на:
     *   - 2/N — стандарт для one-sided спектра (учёт отрицательных частот)
     *   - ×2  — компенсация Hann coherent gain ≈ 0.5
     *           (без неё синусоида амплитуды 1.0 показывалась бы как 0.5)
     *
     * После компенсации: синусоида амплитуды A → амплитуда A на пике её
     * частоты в спектре. Параsевалова норма НЕ соблюдается (4/N != 2/N),
     * но для отображения пиков и сравнения — корректно.
     */
    fun amplitudeSpectrum(input: FloatArray): FloatArray {
        val n = nearestPow2Down(input.size)
        if (n < 2) return FloatArray(0)

        val re = FloatArray(n)
        val im = FloatArray(n)

        // Окно Ханна: w[i] = 0.5 * (1 - cos(2π i / (N-1)))
        // Уменьшает side-lobes до -32 dB (vs -13 dB у прямоугольного окна).
        val denom = (n - 1).toFloat()
        for (i in 0 until n) {
            val w = 0.5f * (1f - cos(2.0 * PI * i / denom).toFloat())
            re[i] = input[i] * w
        }

        fftInPlace(re, im)

        val half = n / 2
        val amps = FloatArray(half)
        // 2/N стандарт + ×2 компенсация Hann coherent gain (~0.5) = 4/N.
        val norm = 4f / n
        for (k in 0 until half) {
            amps[k] = hypot(re[k], im[k]) * norm
        }
        return amps
    }

    /** Частота k-го бина при заданной частоте дискретизации. */
    fun binHz(bin: Int, sampleRateHz: Float, fftSize: Int): Float =
        bin * sampleRateHz / fftSize

    /** Ближайшая степень 2 ≤ n. */
    private fun nearestPow2Down(n: Int): Int {
        if (n < 1) return 0
        var p = 1
        while (p * 2 <= n) p = p shl 1
        return p
    }

    /**
     * In-place radix-2 Cooley-Tukey. Длина [re] и [im] = N, N — степень 2.
     * Сложность O(N log N), память O(1) сверх входных массивов.
     */
    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size

        // 1) Bit-reversal permutation — переставляем индексы так, чтобы
        //    рекурсия по чётным/нечётным разделилась без рекурсии.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // 2) Бабочки. Идём по уровням len = 2, 4, 8, ..., N.
        //    На каждом — N/len блоков по len элементов. Внутри блока
        //    делаем len/2 бабочек с поворотным множителем W = exp(-j 2π / len).
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wStepRe = cos(ang).toFloat()
            val wStepIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val tIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + half] = uRe - tRe
                    im[i + k + half] = uIm - tIm
                    // обновление поворотного: w *= W_step
                    val newRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = newRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
