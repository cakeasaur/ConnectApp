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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.CrossCorrelation
import com.example.connectapp.math.Fft
import com.example.connectapp.math.HeatFlux
import com.example.connectapp.math.Integration
import com.example.connectapp.math.Kalman1D
import com.example.connectapp.math.Orientation
import com.example.connectapp.math.computeVibrationStats
import kotlin.math.min
import java.util.Locale

/**
 * DEPRECATED-fallback. Реальное значение приходит параметром в MathSection
 * из настроек DataStore (`AppSettings.sampleRateHz`). Оставляем для
 * локальных тестов и обратной совместимости.
 */
private const val DEFAULT_SAMPLE_RATE_HZ = 10f

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
    a2y: List<TimedPoint>,
    generation: Int = 0,
    advanced: Boolean = false,
    sampleRateHz: Float = DEFAULT_SAMPLE_RATE_HZ,
) {
    Spacer(Modifier.height(8.dp))
    Text("Математический анализ", style = MaterialTheme.typography.titleLarge)
    Text(
        if (advanced)
            "FFT / RMS / Crest / Kurtosis / Tilt / Cross-correlation / Heat flux / Kalman fusion"
        else
            "RMS / Crest / Kurtosis / Tilt / Heat flux  ·  включи Advanced для FFT и спектрограмм",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // === БАЗОВЫЙ НАБОР (всегда виден) ===
    // Это то что преподаватель/пользователь поймёт без знания DSP:
    // числа RMS/Crest/Kurt, наклон в градусах, тепловой поток в Вт/м².

    Text("Дескрипторы вибрации (ISO 10816)", style = MaterialTheme.typography.titleMedium)
    VibrationStatsCard("ax1", a1x)
    VibrationStatsCard("ax2", a2x)

    Text("Ориентация (наклон A1)", style = MaterialTheme.typography.titleMedium)
    TiltCard(a1x, a1y, a1z)

    Text("Тепловой поток (закон Фурье)", style = MaterialTheme.typography.titleMedium)
    HeatFluxCard(t1, t2)

    // === ADVANCED (под флагом) ===
    // FFT, STFT, фазовые портреты, Kalman, интеграция — для тех кто
    // понимает что смотреть. По умолчанию скрыты — не загромождают экран.
    if (!advanced) return

    Text("Спектр вибрации ax1 (FFT, окно Ханна)", style = MaterialTheme.typography.titleMedium)
    FftCard(a1x, sampleRateHz)

    Text(
        "Спектрограмма ax1 (STFT-waterfall, turbo-палитра)",
        style = MaterialTheme.typography.titleMedium
    )
    SpectrogramCard(series = a1x, sampleRateHz = sampleRateHz)

    Text(
        "Orbit / Lissajous (фазовые портреты)",
        style = MaterialTheme.typography.titleMedium
    )
    OrbitTripletCard(a1x = a1x, a1y = a1y, a2x = a2x, a2y = a2y)

    Text(
        "Phosphor persistence (CRT-стиль osc)",
        style = MaterialTheme.typography.titleMedium
    )
    PersistenceCard(series = a1x, generation = generation)

    Text("Cross-correlation ax1 ↔ ax2", style = MaterialTheme.typography.titleMedium)
    CrossCorrCard(a1x, a2x, sampleRateHz)

    Text("Слияние акселерометров (Kalman 1D)", style = MaterialTheme.typography.titleMedium)
    KalmanFusionCard(a1x, a2x, generation)

    Text("Скорость и смещение A1 (∫a·dt, ∫∫a·dt²)", style = MaterialTheme.typography.titleMedium)
    VelocityCard(ax = a1x, ay = a1y, label = "A1")
}

// ============================================================
// FFT — амплитудный спектр
// ============================================================

@Composable
private fun FftCard(series: List<TimedPoint>, sampleRateHz: Float) {
    if (series.size < 32) {
        EmptyCard("Собираю данные… нужно ≥32 отсчётов")
        return
    }
    var logScale by remember { mutableStateOf(false) }

    val winSize = min(series.size, 256)
    val lastT = series.last().t
    val spectrum = remember(series.size, lastT) {
        val arr = FloatArray(winSize)
        val from = series.size - winSize
        for (i in 0 until winSize) arr[i] = series[from + i].value
        Fft.amplitudeSpectrum(arr)
    }
    val fftSize = nearestPow2Down(winSize)
    val nyquist = sampleRateHz / 2f

    var peakBin = 1
    var peakAmp = 0f
    for (i in 1 until spectrum.size) {
        if (spectrum[i] > peakAmp) { peakAmp = spectrum[i]; peakBin = i }
    }
    val peakHz = Fft.binHz(peakBin, sampleRateHz, fftSize)

    NeonCard(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                "пик: %.2f Гц · %.1f · окно %d · fs=%.0f Гц"
                    .format(Locale.ROOT, peakHz, peakAmp, fftSize, sampleRateHz),
                style = MaterialTheme.typography.bodySmall,
                color = NeonTheme.axisText,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.FilterChip(
                selected = logScale,
                onClick = { logScale = !logScale },
                label = { Text("dB", style = MaterialTheme.typography.labelSmall) }
            )
        }
        Spacer(Modifier.height(4.dp))
        FftSpectrumBars(
            spectrum = spectrum,
            nyquistHz = nyquist,
            peakBin = peakBin,
            logScale = logScale,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Столбики спектра с осью X (Гц), опциональной dB-шкалой и маркерами гармоник.
 *
 * Ось X: подписи 0, Nyquist/2, Nyquist.
 * dB-шкала: 20·log10(amp/max), диапазон -60..0 dB.
 * Гармоники: пунктирные линии на 2f, 3f, 4f от доминирующего пика.
 */
@Composable
private fun FftSpectrumBars(
    spectrum: FloatArray,
    nyquistHz: Float,
    peakBin: Int,
    logScale: Boolean,
    modifier: Modifier,
) {
    val barColor      = NeonTheme.accent
    val gridColor     = NeonTheme.gridMajor
    val harmColor     = NeonTheme.warn.copy(alpha = 0.75f)
    val axisTextColor = NeonTheme.axisText
    val DB_RANGE      = 60f

    // Paint кэшируется по density — пересоздаётся только при смене плотности
    // экрана (rotation на foldable, split screen). textSize задаётся один
    // раз в remember-блоке, а не на каждый draw.
    val localDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val textPaint = remember(localDensity) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                (axisTextColor.alpha * 255).toInt(),
                (axisTextColor.red   * 255).toInt(),
                (axisTextColor.green * 255).toInt(),
                (axisTextColor.blue  * 255).toInt()
            )
            textSize = 9f * localDensity
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    Canvas(modifier = modifier) {
        if (spectrum.isEmpty()) return@Canvas
        val n     = spectrum.size
        val w     = size.width
        val hPlot = (size.height - 16f).coerceAtLeast(1f)
        val barW  = w / n
        val maxAmp = (spectrum.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)

        fun ampToY(amp: Float): Float = if (logScale) {
            val dB = (20f * kotlin.math.log10((amp / maxAmp).coerceAtLeast(1e-9f)))
                .coerceIn(-DB_RANGE, 0f)
            hPlot * (1f - (dB + DB_RANGE) / DB_RANGE)
        } else {
            hPlot * (1f - (amp / maxAmp).coerceIn(0f, 1f))
        }

        for (i in 1..3) {
            drawLine(gridColor, Offset(0f, hPlot * i / 4f), Offset(w, hPlot * i / 4f), strokeWidth = 0.5f)
        }

        for (i in 0 until n) {
            val top = ampToY(spectrum[i])
            drawRect(
                color = barColor,
                topLeft = Offset(i * barW, top),
                size = Size(barW.coerceAtLeast(1f) - 0.5f, (hPlot - top).coerceAtLeast(0f))
            )
        }

        if (peakBin > 0) {
            for (mult in 2..4) {
                val bin = peakBin * mult
                if (bin < n) {
                    val x = bin * barW + barW / 2f
                    drawLine(
                        harmColor, Offset(x, 0f), Offset(x, hPlot), strokeWidth = 1.2f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect
                            .dashPathEffect(floatArrayOf(4f, 3f))
                    )
                }
            }
        }

        listOf(0f, nyquistHz / 2f, nyquistHz).forEach { hz ->
            val x = if (nyquistHz > 0f) (hz / nyquistHz) * w else 0f
            drawLine(NeonTheme.axisLine, Offset(x, hPlot), Offset(x, hPlot + 4f), strokeWidth = 1f)
            val label = if (hz == 0f) "0" else "%.1fГц".format(Locale.ROOT, hz)
            val tw = textPaint.measureText(label)
            drawIntoCanvas { it.nativeCanvas.drawText(label, (x - tw / 2f).coerceIn(0f, w - tw), hPlot + 13f, textPaint) }
        }
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
    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = NeonTheme.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            NeonStatBox("RMS", "%.1f".format(Locale.ROOT, stats.rms))
            NeonStatBox("Peak", "%.1f".format(Locale.ROOT, stats.peak))
            NeonStatBox("Crest", "%.2f".format(Locale.ROOT, stats.crest), warn = stats.crest > 4f)
            NeonStatBox("Kurt", "%.2f".format(Locale.ROOT, stats.kurtosis), warn = stats.kurtosis > 4f)
        }
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

    NeonCard(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Row(Modifier.fillMaxSize()) {
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
                    color = NeonTheme.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace)
                Text("Roll:  %+.1f°".format(Locale.ROOT, roll),
                    color = NeonTheme.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("|a| = %.0f LSB".format(Locale.ROOT, mag),
                    color = NeonTheme.axisText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace)
                Text(
                    "норма ~1000 (= 1g, покой)",
                    color = NeonTheme.axisText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** Круглый уровень с пузырьком, смещённым по (pitch, roll). */
@Composable
private fun BubbleLevel(pitch: Float, roll: Float, modifier: Modifier) {
    val frameColor = NeonTheme.axisText
    val bubbleColor = NeonTheme.accent
    val centerCrossColor = NeonTheme.gridMajor
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
private fun CrossCorrCard(a1: List<TimedPoint>, a2: List<TimedPoint>, sampleRateHz: Float) {
    val n = min(a1.size, a2.size)
    if (n < 32) {
        EmptyCard("Собираю данные… нужно ≥32 пар отсчётов")
        return
    }
    val maxLag = min(20, n / 4)
    val lastT1 = a1.last().t
    val lastT2 = a2.last().t
    val corr = remember(n, lastT1, lastT2) {
        // Парим ПО TIMESTAMP через findNearest, не по индексу. При
        // рассинхронизации (потеря пакетов, разный rate) index-pairing
        // давал бы корреляцию temporally misaligned точек → мусор.
        val x = FloatArray(n)
        val y = FloatArray(n)
        val from1 = a1.size - n
        for (i in 0 until n) {
            val xPoint = a1[from1 + i]
            x[i] = xPoint.value
            y[i] = (findNearest(a2, xPoint.t) ?: xPoint).value
        }
        CrossCorrelation.normalized(x, y, maxLag)
    }
    val (bestLag, bestVal) = CrossCorrelation.bestLag(corr, maxLag)
    val lagSec = bestLag / sampleRateHz

    NeonCard(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        Text(
            "best lag: %+d отсчётов (%+.2f с) · R=%.3f"
                .format(Locale.ROOT, bestLag, lagSec, bestVal),
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
        Text(
            if (bestLag > 0) "ax2 отстаёт от ax1 на $bestLag отсчётов"
            else if (bestLag < 0) "ax2 опережает ax1 на ${-bestLag} отсчётов"
            else "сигналы синхронны",
            color = NeonTheme.textPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        CrossCorrCurve(corr, maxLag, Modifier.fillMaxSize())
    }
}

@Composable
private fun CrossCorrCurve(corr: FloatArray, maxLag: Int, modifier: Modifier) {
    val lineColor = NeonTheme.accent
    val zeroColor = NeonTheme.gridMajor
    val peakColor = NeonTheme.alert
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

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "ΔT = %.2f °C · Δx = %.0f мм · k = 401 Вт/(м·К) [медь]"
                .format(Locale.ROOT, t2v - t1v, distanceM * 1000),
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            NeonStatBox("∇T", "%+.1f К/м".format(Locale.ROOT, gradient))
            NeonStatBox("q", "%+.0f Вт/м²".format(Locale.ROOT, q))
            NeonStatBox(
                if (q > 0) "→" else if (q < 0) "←" else "—",
                if (q > 0) "T1→T2" else if (q < 0) "T2→T1" else "нет"
            )
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
    // отсчётах с прошлого раза. generation как ключ — при SensorDataBus.clear()
    // инкрементится → пересоздаём Kalman с нуля.
    val kalman = remember(generation) { Kalman1D(processVar = 0.5f, measVar1 = 5f, measVar2 = 8f) }
    val lastSeenT = remember(generation) { LongArray(1) { Long.MIN_VALUE } }
    // estimate/covariance — observable state, обновляется ингестом в LaunchedEffect.
    // Раньше mutation была внутри remember{} — composition cancel посередине
    // ингеста означал двойную обработку точек на retry и дрейф оценки.
    var est by remember(generation) { mutableStateOf(0f to 1f) }
    val lastT1 = a1.last().t
    LaunchedEffect(generation, lastT1) {
        val n1 = a1.size; val n2 = a2.size
        val pairCount = min(n1, n2)
        val startT = lastSeenT[0]
        var startIdx = pairCount - 1
        while (startIdx >= 0 && a1[startIdx + (n1 - pairCount)].t > startT) startIdx--
        startIdx++
        val off1 = n1 - pairCount
        val off2 = n2 - pairCount
        for (i in startIdx until pairCount) {
            kalman.update(a1[i + off1].value, a2[i + off2].value)
        }
        // ВАЖНО: lastSeenT обновляем ПЕРЕД публикацией — если LaunchedEffect
        // отменится сразу после ингеста, ретриггер не будет двойной обработки.
        lastSeenT[0] = lastT1
        est = kalman.estimate to kalman.covariance
    }
    val estimate = est
    val raw1 = a1.last().value
    val raw2 = a2.last().value

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "две оценки ax → одна оптимальная (Q=0.5, R1=5, R2=8)",
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            NeonStatBox("ax1", "%+.1f".format(Locale.ROOT, raw1))
            NeonStatBox("ax2", "%+.1f".format(Locale.ROOT, raw2))
            NeonStatBox("x̂", "%+.1f".format(Locale.ROOT, estimate.first))
            NeonStatBox("P", "%.3f".format(Locale.ROOT, estimate.second))
        }
    }
}

// ============================================================
// Velocity / Displacement — двойное интегрирование акселерометра
// ============================================================

/**
 * Два чарта: скорость (∫a·dt) и смещение (∫v·dt = ∫∫a·dt²).
 *
 * Единицы — LSB·с и LSB·с², не физические мм/с и мм. Для перевода
 * нужна калибровка акселерометра (sensitivity, мг/LSB). Без калибровки
 * сигнал полезен для СРАВНИТЕЛЬНОГО анализа: "стало больше/меньше",
 * "совпадает ли с предыдущей сессией".
 *
 * Drift-коррекция (linear detrend) встроена в [Integration.integrate] —
 * DC-смещение не накапливается. На тихом (~покой) сигнале оба чарта
 * будут около нуля, при вибрации покажут волны.
 */
@Composable
private fun VelocityCard(ax: List<TimedPoint>, ay: List<TimedPoint>, label: String) {
    if (ax.size < 10) {
        EmptyCard("$label: нужно ≥10 отсчётов")
        return
    }
    val lastTax = ax.last().t
    val lastTay = ay.lastOrNull()?.t ?: lastTax

    // Все четыре ряда в одном remember: dispX/dispY зависят от velX/velY,
    // поэтому объединяем — иначе вложенный remember со stale-ключами мог
    // пересчитывать displacement позже velocity на один recompose.
    val integrated = remember(ax.size, lastTax, ay.size, lastTay) {
        val vx = Integration.integrate(ax)
        val vy = Integration.integrate(ay)
        val dx = if (vx.size >= 2) Integration.integrate(vx) else emptyList<TimedPoint>()
        val dy = if (vy.size >= 2) Integration.integrate(vy) else emptyList<TimedPoint>()
        arrayOf(vx, vy, dx, dy)
    }
    val velX  = integrated[0]
    val velY  = integrated[1]
    val dispX = integrated[2]
    val dispY = integrated[3]

    val velColors  = listOf(androidx.compose.ui.graphics.Color(0xFFFF6B6B), androidx.compose.ui.graphics.Color(0xFF69F0AE))
    val dispColors = listOf(androidx.compose.ui.graphics.Color(0xFFFFB74D), androidx.compose.ui.graphics.Color(0xFF80CBC4))

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label скорость (LSB·с)",
            style = MaterialTheme.typography.labelMedium,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            NeonChart(
                seriesList = listOf(
                    NeonSeries(velX, velColors[0], "vx"),
                    NeonSeries(velY, velColors[1], "vy"),
                ),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "$label смещение (LSB·с²)",
            style = MaterialTheme.typography.labelMedium,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            NeonChart(
                seriesList = listOf(
                    NeonSeries(dispX, dispColors[0], "dx"),
                    NeonSeries(dispY, dispColors[1], "dy"),
                ),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "detrend-коррекция дрейфа включена · физические единицы требуют калибровки LSB→g",
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================
// helpers
// ============================================================

@Composable
private fun EmptyCard(text: String) {
    NeonCard(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = NeonTheme.axisText,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

