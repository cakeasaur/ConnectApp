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
import kotlin.math.log10

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
// hop = 4 sample = 93.75% overlap. Плотный водопад — каждый новый
// отсчёт сдвигает окно на 1 столбец, видна плавная эволюция спектра.
// (Классический STFT использует hop = FFT_SIZE/2 = 32 для 50% overlap;
// мы делаем гораздо плотнее для красоты.)
private const val STEP_SAMPLES = 4

/**
 * Динамический диапазон для dB-шкалы (в децибелах ниже global max).
 * Стандарт audio-визуализации: -60 dB ≈ 1/1000 от пика, удобно различать
 * тихие сигналы. Больше → сильнее видно шум, меньше → высокий контраст.
 */
private const val DB_DYNAMIC_RANGE = 60f

@Composable
fun SpectrogramCard(
    series: List<TimedPoint>,
    sampleRateHz: Float,
    modifier: Modifier = Modifier,
    generation: Int = 0,
) {
    if (series.size < FFT_SIZE) {
        EmptyHint("Нужно ≥$FFT_SIZE отсчётов для STFT")
        return
    }

    // Pre-allocated bitmap + pixel-буфер — переживают recompose, заполняются
    // на месте. Раньше каждый recompose делал Bitmap.createBitmap (25KB
    // ARGB) + IntArray(6400) → ~250 КБ/сек churn в native bitmap heap.
    val freqBins = FFT_SIZE / 2
    val bitmap = remember { android.graphics.Bitmap.createBitmap(MAX_COLUMNS, freqBins, android.graphics.Bitmap.Config.ARGB_8888) }
    val pixelBuf = remember { IntArray(MAX_COLUMNS * freqBins) }
    // AGC-референс нормировки [0]. Сбрасывается на новой сессии (generation),
    // иначе яркость подстраивается под прошлую плату. См. attack/decay в fill.
    val agc = remember(generation) { floatArrayOf(1e-6f) }

    val lastT = series.last().t
    // Перерасчёт при изменении данных. Заполнение идемпотентно (одни и те
    // же данные → один результат), поэтому side-effect в remember безопасен
    // даже при отмене composition. Возвращаем число заполненных колонок —
    // оно же даёт честный охват по времени и src-rect для отрисовки.
    val nCols = remember(series.size, lastT) {
        fillSpectrogramBitmap(bitmap, pixelBuf, series, freqBins, agc)
    }

    val nyquist = sampleRateHz / 2f
    val peakHz = remember(series.size, lastT) { findPeakFreq(series, sampleRateHz) }

    // Реальная глубина по времени = заполненные колонки × hop. Раньше подпись
    // считалась от MAX_COLUMNS и завышала охват (буфер до него не дорастает).
    val dataSpanSec = nCols * STEP_SAMPLES / sampleRateHz
    val hopMs = STEP_SAMPLES * 1000 / sampleRateHz
    NeonCard(modifier = modifier.fillMaxWidth().height(240.dp)) {
        Text(
            "STFT окно %d · hop %.0fмс · 0..%.1f Гц · пик %.2f Гц · dB-scale −%.0fdB..0"
                .format(Locale.ROOT, FFT_SIZE, hopMs, nyquist, peakHz, DB_DYNAMIC_RANGE),
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    drawIntoCanvas { canvas: androidx.compose.ui.graphics.Canvas ->
                        val nativeCanvas: NativeCanvas = canvas.nativeCanvas
                        // Рисуем только заполненные колонки (они прижаты вправо),
                        // растягивая на всю ширину — нет неподписанной чёрной
                        // зоны слева, а ось времени совпадает с подписями.
                        val src = android.graphics.Rect(MAX_COLUMNS - nCols, 0, MAX_COLUMNS, freqBins)
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
                // X-метки времени: старые слева, "сейчас" справа. Данные
                // растянуты на всю ширину, центр оси = половина охвата.
                Text(
                    "−%.0fs".format(Locale.ROOT, dataSpanSec / 2f),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.BottomCenter)
                )
                Text(
                    "now",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.BottomEnd)
                )
            }
    }
}

// ============================================================
// Computation
// ============================================================

/**
 * Заполняет переиспользуемый [bitmap] спектрограммой по [series].
 * Все аллокации — только в self-arg ([pixelBuf]); bitmap shape = MAX_COLUMNS×freqBins.
 *
 * Если новых данных меньше чем MAX_COLUMNS окон — левая часть заливается
 * чёрным (clear).
 *
 * Нормализация: один глобальный max по всем колонкам → 0..1 → LUT(turbo).
 * Bin=0 (DC) пропущен — иначе гравитационная компонента (~1000 LSB на az)
 * прибивает всё остальное.
 */
private fun fillSpectrogramBitmap(
    bitmap: Bitmap,
    pixelBuf: IntArray,
    series: List<TimedPoint>,
    freqBins: Int,
    agc: FloatArray
): Int {
    // Сколько окон поместится: округление сверху на маленьких series.
    val maxStartIdx = series.size - FFT_SIZE
    val totalWindows = (maxStartIdx / STEP_SAMPLES) + 1
    val nCols = minOf(MAX_COLUMNS, totalWindows.coerceAtLeast(1))

    // Считаем спектры N последних окон.
    val columns = Array(nCols) { col ->
        val start = (maxStartIdx - (nCols - 1 - col) * STEP_SAMPLES).coerceAtLeast(0)
        val arr = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) arr[i] = series[start + i].value
        Fft.amplitudeSpectrum(arr)
    }

    // Max текущего кадра (без DC).
    var frameMax = 1e-6f
    for (col in columns) {
        for (i in 1 until col.size) if (col[i] > frameMax) frameMax = col[i]
    }
    // AGC-нормировка: подъём мгновенный (новый сильный сигнал не клиппится),
    // спад медленный (0.05/кадр). Раньше нормировали по per-frame max — и весь
    // водопад «дышал» цветом при затухании, колонки нельзя было сравнивать.
    val ref = if (frameMax > agc[0]) frameMax else agc[0] + (frameMax - agc[0]) * 0.05f
    agc[0] = ref.coerceAtLeast(1e-6f)
    val invMax = 1f / agc[0]

    // Если nCols < MAX_COLUMNS, левая часть буфера зануляется — иначе
    // на previous поколении могли остаться "хвосты".
    java.util.Arrays.fill(pixelBuf, android.graphics.Color.BLACK)
    val offsetCol = MAX_COLUMNS - nCols  // новые колонки прижаты к правому краю

    // dB-маппинг: amp_norm = amp/max ∈ (0..1] → 20·log10(amp_norm) ∈ (-∞..0]
    // → клампим в [-DB_DYNAMIC_RANGE..0] → нормируем в [0..1] для LUT.
    // EPS защищает log10 от нуля (тихие бины → max attenuation).
    val EPS = 1e-9f
    val invRange = 1f / DB_DYNAMIC_RANGE
    for (col in 0 until nCols) {
        val spectrum = columns[col]
        for (row in 0 until freqBins) {
            val ampNorm = (spectrum.getOrElse(row) { 0f } * invMax).coerceIn(0f, 1f)
            val dB = 20f * log10(ampNorm + EPS)
            // dB ∈ (-180..0] для EPS=1e-9. Клампим в [-DB_DYNAMIC_RANGE..0].
            val intensity = ((dB + DB_DYNAMIC_RANGE) * invRange).coerceIn(0f, 1f)
            // Low-freq внизу: pixel row = freqBins-1-row.
            val pixelRow = freqBins - 1 - row
            pixelBuf[pixelRow * MAX_COLUMNS + offsetCol + col] = TurboLut.colorFor(intensity)
        }
    }
    bitmap.setPixels(pixelBuf, 0, MAX_COLUMNS, 0, 0, MAX_COLUMNS, freqBins)
    return nCols
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
    NeonCard(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = NeonTheme.axisText,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
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
