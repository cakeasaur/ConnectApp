package com.example.connectapp.ui.graph

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.Fft
import java.util.Locale

/**
 * Спектрограмма (waterfall) — 2D-карта время × частота × амплитуда.
 *
 * X-ось = время (старое слева, новое справа), Y-ось = частота
 * (0 внизу, Nyquist=fs/2 вверху), цвет = амплитуда (turbo-палитра,
 * стандартная для научной виз).
 *
 * Алгоритм STFT (short-time Fourier transform):
 *   - окно [fftSize] отсчётов скользит по сигналу с шагом [step]
 *     (50% overlap — стандарт для STFT, hop = fftSize/2)
 *   - на каждом окне — FFT с окном Ханна (уже встроено в [Fft])
 *   - результат = колонка спектра, лепится справа к водопаду
 *
 * Рендеринг — через Bitmap.setPixels + drawImage: 6400 ячеек (200×32)
 * перерисовать как Canvas.drawRect 60fps = 384k вызовов/сек, слишком
 * дорого. Bitmap пишется один раз за recompose (~5мс на 200 FFT-64),
 * потом масштабируется на канву одним вызовом drawImage.
 */
private const val FFT_SIZE = 64        // 64 samples / 10Hz = 6.4s окно
private const val MAX_COLUMNS = 200    // ~20с истории при 100мс шаге
private const val STEP_SAMPLES = 4     // 50% overlap → плотный водопад

@Composable
fun SpectrogramCard(
    series: List<TimedPoint>,
    sampleRateHz: Float,
    modifier: Modifier = Modifier
) {
    if (series.size < FFT_SIZE) {
        EmptyHint("Нужно ≥$FFT_SIZE отсчётов для STFT")
        return
    }

    // Пересчёт спектрограммы при каждом изменении данных. Ключ — last().t
    // и size, чтобы invalidate только когда реально новые точки пришли.
    val lastT = series.last().t
    val freqBins = FFT_SIZE / 2
    val bitmap = remember(series.size, lastT) {
        buildSpectrogramBitmap(series, freqBins)
    }

    val nyquist = sampleRateHz / 2f
    val peakHz = remember(bitmap) { findPeakFreq(series, sampleRateHz) }

    Card(
        modifier = modifier.fillMaxWidth().height(220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(8.dp).fillMaxSize()) {
            Text(
                "STFT окно %d отсчётов · шаг %d · диапазон 0..%.1f Гц · доминанта %.2f Гц"
                    .format(Locale.ROOT, FFT_SIZE, STEP_SAMPLES, nyquist, peakHz),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    drawIntoCanvas { canvas: androidx.compose.ui.graphics.Canvas ->
                        val nativeCanvas: NativeCanvas = canvas.nativeCanvas
                        val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                        val dst = android.graphics.RectF(0f, 0f, w, h)
                        // FILTER_BITMAP_FLAG default = true (linear interpolation)
                        // даёт сглаженный водопад вместо квадратных пикселей.
                        nativeCanvas.drawBitmap(bitmap, src, dst, null)
                    }
                    // Тонкая рамка
                    drawRect(
                        color = Color.White.copy(alpha = 0.15f),
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1f)
                    )
                }
                // Подпись частот по Y-краю
                Text(
                    "%.1f Гц".format(Locale.ROOT, nyquist),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart)
                )
                Text(
                    "0 Гц",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.BottomStart)
                )
            }
        }
    }
}

// ============================================================
// Computation
// ============================================================

/**
 * Считает [MAX_COLUMNS] окон STFT с конца [series] (новейшее последнее).
 * Использует hop = [STEP_SAMPLES] (50% overlap при [FFT_SIZE]=64).
 *
 * Нормализация: один глобальный max по всем колонкам → 0..1 → LUT(turbo).
 */
private fun buildSpectrogramBitmap(series: List<TimedPoint>, freqBins: Int): Bitmap {
    // Сколько окон поместится в буфер.
    val maxStartIdx = series.size - FFT_SIZE
    val totalWindows = (maxStartIdx / STEP_SAMPLES) + 1
    val nCols = minOf(MAX_COLUMNS, totalWindows.coerceAtLeast(1))

    // Колонки от старых к новым: индекс начала окна = maxStartIdx - (nCols-1-col)*STEP_SAMPLES
    val columns = Array(nCols) { col ->
        val start = (maxStartIdx - (nCols - 1 - col) * STEP_SAMPLES).coerceAtLeast(0)
        val arr = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) arr[i] = series[start + i].value
        Fft.amplitudeSpectrum(arr)
    }

    // Global max for normalization. Без bin=0 (DC) — иначе нормализация
    // прибита DC-компонентой и все полосы плохо различимы.
    var maxAmp = 1e-6f
    for (col in columns) {
        for (i in 1 until col.size) if (col[i] > maxAmp) maxAmp = col[i]
    }

    // Bitmap: ширина = nCols, высота = freqBins. Заполняем int[] и одним
    // вызовом setPixels — быстрее чем setPixel по одному.
    val bmp = Bitmap.createBitmap(nCols, freqBins, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(nCols * freqBins)
    val invMax = 1f / maxAmp
    for (col in 0 until nCols) {
        val spectrum = columns[col]
        for (row in 0 until freqBins) {
            val amp = (spectrum.getOrElse(row) { 0f } * invMax).coerceIn(0f, 1f)
            // Low-freq внизу: pixel row = freqBins-1-row (bitmap row 0 = top).
            val pixelRow = freqBins - 1 - row
            pixels[pixelRow * nCols + col] = TurboLut.colorFor(amp)
        }
    }
    bmp.setPixels(pixels, 0, nCols, 0, 0, nCols, freqBins)
    return bmp
}

private fun findPeakFreq(series: List<TimedPoint>, sampleRateHz: Float): Float {
    if (series.size < FFT_SIZE) return 0f
    val arr = FloatArray(FFT_SIZE)
    val from = series.size - FFT_SIZE
    for (i in 0 until FFT_SIZE) arr[i] = series[from + i].value
    val spectrum = Fft.amplitudeSpectrum(arr)
    var peakBin = 1; var peakAmp = 0f
    for (i in 1 until spectrum.size) {
        if (spectrum[i] > peakAmp) { peakAmp = spectrum[i]; peakBin = i }
    }
    return Fft.binHz(peakBin, sampleRateHz, FFT_SIZE)
}

@Composable
private fun EmptyHint(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================
// Turbo colormap LUT (Mikhailov 2019, Google AI)
// ============================================================

/**
 * Turbo — улучшенная замена Jet для научной виз. Перцептуально равномерная,
 * не мерцающая, без "цветных артефактов" Jet'а. Используется в Google Maps,
 * Matplotlib (с 3.4+), TensorBoard.
 *
 * Реализация — кусочно-полиномиальная аппроксимация по таблице 256 цветов.
 * Дёшево (3 умножения на канал), без LUT-памяти.
 *
 * Reference: https://ai.googleblog.com/2019/08/turbo-improved-rainbow-colormap-for.html
 */
private object TurboLut {
    /** [t] в [0,1] → ARGB int (alpha = FF). */
    fun colorFor(t: Float): Int {
        val x = t.coerceIn(0f, 1f)
        // Полиномиальная аппроксимация (https://gist.github.com/mikhailov-work/ee72ba4191942acecc03fe6da94fc73f)
        val r = (34.61f + x * (1172.33f - x * (10793.56f - x * (33300.12f - x * (38394.49f - x * 14825.05f))))).toInt().coerceIn(0, 255)
        val g = (23.31f + x * (557.33f + x * (1225.33f - x * (3574.96f - x * (1073.77f + x * 707.56f))))).toInt().coerceIn(0, 255)
        val b = (27.2f + x * (3211.1f - x * (15327.97f - x * (27814f - x * (22569.18f - x * 6838.66f))))).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
