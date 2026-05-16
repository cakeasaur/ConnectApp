package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.CrossCorrelation
import com.example.connectapp.math.Fft
import com.example.connectapp.math.HeatFlux
import com.example.connectapp.math.Kalman1D
import com.example.connectapp.math.Orientation
import com.example.connectapp.math.computeVibrationStats
import kotlin.math.min
import java.util.Locale

/**
 * Частота дискретизации сенсоров (Гц). Прошивка PIC24 шлёт 10 Hz, mock —
 * тоже 10 Hz. Если поменяешь частоту опроса — поменяй здесь, иначе ось
 * частот FFT и расчёт лагов в секундах будут врать.
 */
private const val SAMPLE_RATE_HZ = 10f

/**
 * Раздел "Математический анализ" под основными графиками.
 *
 * Все расчёты идут реактивно — при каждом новом отсчёте от датчиков
 * Compose делает recompose, математика пересчитывается. Стоимость:
 *   FFT-256        ~0.5 мс
 *   VibrationStats ~0.1 мс
 *   CrossCorr-30   ~0.3 мс
 *   Orientation    <0.01 мс
 * Итого <2 мс на полный пересчёт — Compose 60 fps не страдает.
 */
/**
 * @param generation монотонный counter из SensorDataBus, инкрементируется
 *   при clear(). Stateful-математика (Kalman) использует его как ключ
 *   для сброса собственного накопленного состояния.
 */
@Composable
fun MathSection(
    t1: List<TimedPoint>,
    t2: List<TimedPoint>,
    a1x: List<TimedPoint>,
    a1y: List<TimedPoint>,
    a1z: List<TimedPoint>,
    a2x: List<TimedPoint>,
    generation: Int = 0
) {
    Spacer(Modifier.height(8.dp))
    Text("Математический анализ", style = MaterialTheme.typography.titleLarge)
    Text(
        "FFT / RMS / Crest / Kurtosis / Tilt / Cross-correlation / Heat flux / Kalman fusion",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("Спектр вибрации ax1 (FFT, окно Ханна)", style = MaterialTheme.typography.titleMedium)
    FftCard(a1x)

    Text(
        "Спектрограмма ax1 (STFT-waterfall, turbo-палитра)",
        style = MaterialTheme.typography.titleMedium
    )
    SpectrogramCard(series = a1x, sampleRateHz = SAMPLE_RATE_HZ)

    Text("Дескрипторы вибрации (ISO 10816)", style = MaterialTheme.typography.titleMedium)
    VibrationStatsCard("ax1", a1x)
    VibrationStatsCard("ax2", a2x)

    Text("Ориентация (наклон A1)", style = MaterialTheme.typography.titleMedium)
    TiltCard(a1x, a1y, a1z)

    Text("Cross-correlation ax1 ↔ ax2", style = MaterialTheme.typography.titleMedium)
    CrossCorrCard(a1x, a2x)

    Text("Тепловой поток (закон Фурье)", style = MaterialTheme.typography.titleMedium)
    HeatFluxCard(t1, t2)

    Text("Слияние акселерометров (Kalman 1D)", style = MaterialTheme.typography.titleMedium)
    KalmanFusionCard(a1x, a2x, generation)
}

// ============================================================
// FFT — амплитудный спектр
// ============================================================

@Composable
private fun FftCard(series: List<TimedPoint>) {
    if (series.size < 32) {
        EmptyCard("Собираю данные… нужно ≥32 отсчётов")
        return
    }
    // ВСЁ вычисление вместе с аллокацией FloatArray — внутри remember.
    // Ключ: размер + timestamp последней точки. Список TimedPoint
    // создаётся новый каждый recompose (StateFlow), поэтому ссылочное
    // равенство `series` всегда false → нужен стабильный ключ.
    val winSize = min(series.size, 256)
    val lastT = series.last().t
    val spectrum = remember(series.size, lastT) {
        val arr = FloatArray(winSize)
        val from = series.size - winSize
        for (i in 0 until winSize) arr[i] = series[from + i].value
        Fft.amplitudeSpectrum(arr)
    }
    val fftSize = nearestPow2Down(winSize)
    val nyquist = SAMPLE_RATE_HZ / 2f

    // Поиск доминирующего пика (без бина 0 — это DC).
    var peakBin = 1
    var peakAmp = 0f
    for (i in 1 until spectrum.size) {
        if (spectrum[i] > peakAmp) { peakAmp = spectrum[i]; peakBin = i }
    }
    val peakHz = Fft.binHz(peakBin, SAMPLE_RATE_HZ, fftSize)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().height(200.dp)
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            Text(
                "пик: %.2f Гц · амплитуда %.1f · окно %d отсчётов · fs=%.0f Гц"
                    .format(Locale.ROOT, peakHz, peakAmp, fftSize, SAMPLE_RATE_HZ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FftSpectrumBars(
                spectrum = spectrum,
                nyquistHz = nyquist,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Рисует столбики спектра. X — частота 0..Nyquist, Y — амплитуда (auto-scale). */
@Composable
private fun FftSpectrumBars(spectrum: FloatArray, nyquistHz: Float, modifier: Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Canvas(modifier = modifier) {
        if (spectrum.isEmpty()) return@Canvas
        val maxAmp = (spectrum.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
        val n = spectrum.size
        val w = size.width
        val h = size.height
        val barW = w / n

        // Сетка по Y: 4 горизонтальных линии.
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        for (i in 0 until n) {
            val amp = spectrum[i] / maxAmp
            val barH = amp * h
            drawRect(
                color = barColor,
                topLeft = Offset(i * barW, h - barH),
                size = Size(barW.coerceAtLeast(1f) - 0.5f, barH)
            )
        }

        // Заголовок карточки сообщает доминирующий пик в Hz, поэтому
        // подписи частот на самом Canvas не рисуем — экономим место.
        @Suppress("UNUSED_VARIABLE") val nh = nyquistHz
    }
}

private fun nearestPow2Down(n: Int): Int {
    if (n < 1) return 0
    var p = 1
    while (p * 2 <= n) p = p shl 1
    return p
}

// ============================================================
// Vibration Stats — RMS / Peak / Crest / Kurtosis
// ============================================================

@Composable
private fun VibrationStatsCard(label: String, series: List<TimedPoint>) {
    if (series.isEmpty()) {
        EmptyCard("$label: жду данные…")
        return
    }
    // Аллокация FloatArray вместе с вычислением — внутри remember, иначе
    // 4 × 128 floats каждые 100мс летят в GC впустую.
    val winSize = min(series.size, 128)
    val lastT = series.last().t
    val stats = remember(series.size, lastT) {
        val arr = FloatArray(winSize)
        val from = series.size - winSize
        for (i in 0 until winSize) arr[i] = series[from + i].value
        computeVibrationStats(arr)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatBox("RMS", "%.1f".format(Locale.ROOT, stats.rms))
            StatBox("Peak", "%.1f".format(Locale.ROOT, stats.peak))
            StatBox("Crest", "%.2f".format(Locale.ROOT, stats.crest), warn = stats.crest > 4f)
            StatBox("Kurt", "%.2f".format(Locale.ROOT, stats.kurtosis), warn = stats.kurtosis > 4f)
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = if (warn) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// Tilt — пузырьковый уровень
// ============================================================

@Composable
private fun TiltCard(ax: List<TimedPoint>, ay: List<TimedPoint>, az: List<TimedPoint>) {
    // Если данных нет — НЕ фабрикуем фейковую ориентацию (0,0,1000 = "горизонт"),
    // иначе пользователь видит ровный пузырёк и думает, что плата лежит. Лучше
    // явно сказать "жду данные".
    val axV = ax.lastOrNull()?.value
    val ayV = ay.lastOrNull()?.value
    val azV = az.lastOrNull()?.value
    if (axV == null || ayV == null || azV == null) {
        EmptyCard("Жду показаний акселерометра A1…")
        return
    }
    val pitch = Orientation.pitchDeg(axV, ayV, azV)
    val roll = Orientation.rollDeg(axV, ayV, azV)
    val mag = Orientation.magnitude(axV, ayV, azV)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().height(220.dp)
    ) {
        Row(Modifier.padding(12.dp).fillMaxSize()) {
            // Левая половина — пузырьковый уровень
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                BubbleLevel(pitch = pitch, roll = roll, modifier = Modifier.fillMaxSize())
            }
            // Правая половина — числа
            Column(
                Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Pitch: %+.1f°".format(Locale.ROOT, pitch),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace)
                Text("Roll:  %+.1f°".format(Locale.ROOT, roll),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("|a| = %.0f LSB".format(Locale.ROOT, mag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "норма ~1000 (= 1g, покой)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Круглый уровень с пузырьком, смещённым по (pitch, roll). */
@Composable
private fun BubbleLevel(pitch: Float, roll: Float, modifier: Modifier) {
    val frameColor = MaterialTheme.colorScheme.onSurface
    val bubbleColor = MaterialTheme.colorScheme.primary
    val centerCrossColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f - 4f

        // Внешний круг
        drawCircle(color = frameColor, radius = r, center = Offset(cx, cy), style = Stroke(width = 2f))
        // Внутренние кольца — деления каждые 30°
        for (frac in listOf(0.33f, 0.66f)) {
            drawCircle(color = frameColor.copy(alpha = 0.25f), radius = r * frac, center = Offset(cx, cy), style = Stroke(width = 1f))
        }
        // Перекрестие через центр
        drawLine(centerCrossColor.copy(alpha = 0.5f), Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f)
        drawLine(centerCrossColor.copy(alpha = 0.5f), Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f)

        // Пузырёк: смещение пропорционально углу, ограничено границей.
        // На 90° — пузырёк уезжает на край.
        val maxAngle = 45f
        val dx = (roll / maxAngle).coerceIn(-1f, 1f) * r
        val dy = (-pitch / maxAngle).coerceIn(-1f, 1f) * r  // -pitch: вверх когда наклон вперёд
        drawCircle(color = bubbleColor, radius = r * 0.18f, center = Offset(cx + dx, cy + dy))
    }
}

// ============================================================
// Cross-correlation A1 ↔ A2
// ============================================================

@Composable
private fun CrossCorrCard(a1: List<TimedPoint>, a2: List<TimedPoint>) {
    val n = min(a1.size, a2.size)
    if (n < 32) {
        EmptyCard("Собираю данные… нужно ≥32 пар отсчётов")
        return
    }
    val maxLag = min(20, n / 4)
    val lastT1 = a1.last().t
    val lastT2 = a2.last().t
    val corr = remember(n, lastT1, lastT2) {
        val x = FloatArray(n)
        val y = FloatArray(n)
        val from1 = a1.size - n
        val from2 = a2.size - n
        for (i in 0 until n) {
            x[i] = a1[from1 + i].value
            y[i] = a2[from2 + i].value
        }
        CrossCorrelation.normalized(x, y, maxLag)
    }
    val (bestLag, bestVal) = CrossCorrelation.bestLag(corr, maxLag)
    val lagSec = bestLag / SAMPLE_RATE_HZ

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().height(180.dp)
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            Text(
                "best lag: %+d отсчётов (%+.2f с) · R=%.3f"
                    .format(Locale.ROOT, bestLag, lagSec, bestVal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (bestLag > 0) "ax2 отстаёт от ax1 на $bestLag отсчётов"
                else if (bestLag < 0) "ax2 опережает ax1 на ${-bestLag} отсчётов"
                else "сигналы синхронны",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            CrossCorrCurve(corr, maxLag, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CrossCorrCurve(corr: FloatArray, maxLag: Int, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val peakColor = Color(0xFFD32F2F)
    Canvas(modifier = modifier) {
        if (corr.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val cy = h / 2f
        // Нулевая линия R = 0
        drawLine(zeroColor, Offset(0f, cy), Offset(w, cy), strokeWidth = 1f)
        // Вертикаль на lag = 0
        val zeroLagX = w * maxLag / corr.size
        drawLine(zeroColor, Offset(zeroLagX, 0f), Offset(zeroLagX, h), strokeWidth = 1f)
        // График корреляции
        var prev = Offset(0f, cy - corr[0] * (h / 2f))
        for (i in 1 until corr.size) {
            val x = w * i / (corr.size - 1).toFloat()
            val y = cy - corr[i] * (h / 2f)
            val p = Offset(x, y)
            drawLine(lineColor, prev, p, strokeWidth = 2f)
            prev = p
        }
        // Маркер максимума
        var bi = 0
        for (i in corr.indices) if (corr[i] > corr[bi]) bi = i
        val px = w * bi / (corr.size - 1).toFloat()
        val py = cy - corr[bi] * (h / 2f)
        drawCircle(peakColor, radius = 5f, center = Offset(px, py))
    }
}

// ============================================================
// Heat flux — закон Фурье
// ============================================================

@Composable
private fun HeatFluxCard(t1: List<TimedPoint>, t2: List<TimedPoint>) {
    val t1v = t1.lastOrNull()?.value
    val t2v = t2.lastOrNull()?.value
    if (t1v == null || t2v == null) {
        EmptyCard("Жду температуру…")
        return
    }
    // Допущения: датчики разнесены на 5 см по медному стержню.
    val distanceM = 0.05f
    val q = HeatFlux.compute(t1v, t2v, distanceM, HeatFlux.Conductivity.COPPER)
    val gradient = (t2v - t1v) / distanceM

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "ΔT = %.2f °C · Δx = %.0f мм · k = 401 Вт/(м·К) [медь]"
                    .format(Locale.ROOT, t2v - t1v, distanceM * 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                StatBox("∇T", "%+.1f К/м".format(Locale.ROOT, gradient))
                StatBox("q", "%+.0f Вт/м²".format(Locale.ROOT, q))
                StatBox(
                    if (q > 0) "→" else if (q < 0) "←" else "—",
                    if (q > 0) "T1→T2" else if (q < 0) "T2→T1" else "нет"
                )
            }
        }
    }
}

// ============================================================
// Kalman fusion — слияние двух акселерометров по ax
// ============================================================

@Composable
private fun KalmanFusionCard(a1: List<TimedPoint>, a2: List<TimedPoint>, generation: Int) {
    val n = min(a1.size, a2.size)
    if (n < 5) {
        EmptyCard("Жду данные…")
        return
    }
    // Kalman держим в remember МЕЖДУ recompose'ами и шагаем только на новых
    // отсчётах с прошлого раза. Раньше каждый recompose выполнял O(n) проход
    // по всему окну — на 600 точках это лишние ~3k mul/add впустую.
    //
    // generation как ключ remember: при SensorDataBus.clear() инкрементится →
    // пересоздаём Kalman с нуля, иначе оценка тащит state от удалённой сессии.
    val kalman = remember(generation) { Kalman1D(processVar = 0.5f, measVar1 = 5f, measVar2 = 8f) }
    // Изменяемый holder для последнего обработанного timestamp.
    // LongArray(1) — простейший mutable Long без MutableState (не дёргаем recompose).
    val lastSeenT = remember(generation) { LongArray(1) { Long.MIN_VALUE } }
    val lastT1 = a1.last().t
    val lastT2 = a2.last().t
    val estimate = remember(lastT1, lastT2) {
        val n1 = a1.size; val n2 = a2.size
        val pairCount = min(n1, n2)
        // Найти первый ещё не обработанный индекс. a1 отсортирован по t (append-only).
        val startT = lastSeenT[0]
        var startIdx = pairCount - 1
        while (startIdx >= 0 && a1[startIdx + (n1 - pairCount)].t > startT) startIdx--
        startIdx++
        val off1 = n1 - pairCount
        val off2 = n2 - pairCount
        for (i in startIdx until pairCount) {
            kalman.update(a1[i + off1].value, a2[i + off2].value)
        }
        lastSeenT[0] = lastT1
        kalman.estimate to kalman.covariance
    }
    val raw1 = a1.last().value
    val raw2 = a2.last().value

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "две оценки ax → одна оптимальная (Q=0.5, R1=5, R2=8)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                StatBox("ax1", "%+.1f".format(Locale.ROOT, raw1))
                StatBox("ax2", "%+.1f".format(Locale.ROOT, raw2))
                StatBox("x̂", "%+.1f".format(Locale.ROOT, estimate.first))
                StatBox("P", "%.3f".format(Locale.ROOT, estimate.second))
            }
        }
    }
}

// ============================================================
// helpers
// ============================================================

@Composable
private fun EmptyCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().height(80.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

