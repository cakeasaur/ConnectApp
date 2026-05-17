package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.Fft
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ============================================================
// Public API
// ============================================================

enum class NeonAxis { LEFT, RIGHT }

/**
 * Одна серия данных для [NeonChart].
 * @param axis к какой Y-оси привязана (LEFT по умолчанию). На акселерометре
 *   az обычно вешают на RIGHT — у него масштаб ~1000 LSB, а ax/ay ±50.
 *   Без разделения az давит маленькие колебания ax/ay в "плоскую линию".
 */
data class NeonSeries(
    val data: List<TimedPoint>,
    val color: Color,
    val label: String,
    val axis: NeonAxis = NeonAxis.LEFT,
)

/** Горизонтальная threshold-линия с alert-сравнением. */
data class NeonThreshold(
    val value: Float,
    val label: String,
    val color: Color = Color(0xFFFF8800),
    val axis: NeonAxis = NeonAxis.LEFT,
)

data class NeonChartConfig(
    val showEnvelope: Boolean = false,
    val showSigma: Boolean = false,
    val thresholds: List<NeonThreshold> = emptyList(),
    val envelopeWindowPoints: Int = 20,
    /**
     * Phase-locked mode: автоматически подбирает временное окно равное
     * 2 периодам доминирующей частоты (FFT первой серии). Сигнал
     * "стабилизируется" — каждый кадр показывает ровно 2 цикла.
     * Полезно для периодической вибрации; для апериодических данных
     * (температура) — fallback на полный диапазон.
     */
    val phaseLock: Boolean = false,
    /** Частота дискретизации для FFT в phase-lock режиме. */
    val sampleRateHz: Float = 10f,
)

/**
 * Кастомный научный line-chart в Canvas.
 *
 * Заменяет Vico для трёх основных графиков (Температура, Аксел-1, Аксел-2).
 * Даёт то что Vico из коробки не умеет:
 *   - две независимые Y-оси (left/right) — критично для акселерометра,
 *     где az(~1000) перебивает ax/ay(±50)
 *   - envelope band (min/max rolling)
 *   - ±1σ scatter background
 *   - neon-стиль с glow (blur + двойной обвод)
 *   - threshold-линии с alert-маркером
 *   - drug-обработка + curve smoothing
 *
 * Архитектура рендеринга:
 *   1. background: тёмная подложка
 *   2. grid: тонкие линии
 *   3. для каждой axis: подписи Y-делений
 *   4. для каждой серии (по порядку, новые поверх):
 *      a. envelope (если включён) — semi-transparent band
 *      b. ±1σ scatter background
 *      c. line с glow (2 прохода: blur halo + solid core)
 *      d. current-point pulse
 *   5. threshold-линии + alert icons
 *   6. X-axis labels
 */
@Composable
fun NeonChart(
    seriesList: List<NeonSeries>,
    modifier: Modifier = Modifier,
    config: NeonChartConfig = NeonChartConfig(),
    crosshair: CrosshairBus? = null,
) {
    val nonEmpty = seriesList.filter { it.data.isNotEmpty() }
    if (nonEmpty.isEmpty()) {
        Box(
            modifier = modifier
                .background(NeonTheme.bg, RoundedCornerShape(8.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "нет данных",
                color = NeonTheme.axisText,
                fontFamily = FontFamily.Monospace,
            )
        }
        return
    }

    val rawLastT = nonEmpty.maxOf { it.data.last().t }
    val rawFirstT = nonEmpty.minOf { it.data.first().t }

    // Phase-lock — переопределяем окно отображения до 2 периодов доминанты.
    // Если периодичность не обнаружена → fallback raw. Считаем без
    // remember — детект всё равно дёшев (FFT-64 ≈ 0.3 мс), а ключ raw lastT
    // меняется на КАЖДОМ новом отсчёте, делая кэш бесполезным.
    val (firstT, lastT) = if (config.phaseLock) {
        detectPhaseLockWindow(nonEmpty[0].data, config.sampleRateHz, rawFirstT, rawLastT)
            ?: (rawFirstT to rawLastT)
    } else rawFirstT to rawLastT

    // bounds считаются по ВСЕМ данным серий (не по phase-lock окну) — иначе
    // Y-масштаб скачет на каждом новом отсчёте. БЕЗ remember: ring-buffer
    // фиксированной длины делал nonEmpty.size константой и bounds залипали
    // на устаревших значениях. Считаем напрямую — O(N) проход дёшев.
    val bounds = computeBounds(nonEmpty, config.thresholds)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NeonTheme.bg)
            // Tap-обработчик для crosshair. crosshair == null → отключаем
            // pointerInput полностью (чарт может рендериться без курсора).
            .let { m ->
                if (crosshair == null) m else m.pointerInput(crosshair) {
                    detectTapGestures { offset ->
                        if (lastT <= firstT) return@detectTapGestures
                        val padR = if (nonEmpty.any { it.axis == NeonAxis.RIGHT }) PAD_RIGHT_WITH_AXIS else PAD_RIGHT_BASE
                        val plotL = PAD_LEFT
                        val plotR = size.width - padR
                        if (offset.x < plotL || offset.x > plotR) return@detectTapGestures
                        val frac = ((offset.x - plotL) / (plotR - plotL)).coerceIn(0f, 1f)
                        crosshair.tap(firstT + (frac * (lastT - firstT)).toLong())
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawNeonChart(nonEmpty, bounds, config, firstT, lastT)
        }
        // Легенда сверху-слева.
        LegendRow(
            nonEmpty.map { it.label to it.color },
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        )
        // Crosshair overlay — общий с sync-bus.
        if (crosshair != null) {
            CrosshairOverlay(crosshair, firstT, lastT, nonEmpty)
        }
    }
}

// ============================================================
// Theme
// ============================================================

object NeonTheme {
    val bg = Color(0xFF050B14)           // deep navy фон карточек
    val gridMajor = Color(0xFF1A2A40)
    val gridMinor = Color(0xFF0F1A2A)
    val axisText = Color(0xFF8090B0)     // приглушённый цвет для меток/осей
    val axisLine = Color(0xFF2A3A55)
    val crosshair = Color(0xFFFFFFFF)
    /** Основной текст значений — яркий, моноспейс. */
    val textPrimary = Color(0xFFE0EAFF)
    /** Акцентный неон — для подсветки активных значений / threshold. */
    val accent = Color(0xFF4FC3F7)
    val warn = Color(0xFFFFAA00)
    val alert = Color(0xFFFF5252)
}

/** Тонировка цвета серии для glow — alpha halo. */
private fun Color.glow(a: Float = 0.35f) = copy(alpha = a)

// ============================================================
// Reusable Card / StatBox styled like NeonChart — для математических
// карточек ниже на экране Графиков. Единый visual language со scientific-
// scope эстетикой.
// ============================================================

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = NeonTheme.bg),
        shape = RoundedCornerShape(8.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

/**
 * Stat-box для значений — лейбл сверху приглушённо, число снизу яркое
 * моноспейсом. [warn]=true подсвечивает красным (для Crest/Kurt overflow,
 * threshold crossings).
 */
@Composable
fun NeonStatBox(
    label: String,
    value: String,
    warn: Boolean = false,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = NeonTheme.axisText,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            color = if (warn) NeonTheme.alert else NeonTheme.textPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ============================================================
// Bounds computation
// ============================================================

private data class AxisBounds(val yMin: Float, val yMax: Float) {
    val range: Float get() = (yMax - yMin).coerceAtLeast(1e-6f)
}

private data class ChartBounds(val left: AxisBounds, val right: AxisBounds?)

private fun computeBounds(
    series: List<NeonSeries>,
    thresholds: List<NeonThreshold>
): ChartBounds {
    fun boundsFor(axis: NeonAxis): AxisBounds? {
        val relevant = series.filter { it.axis == axis }
        val thr = thresholds.filter { it.axis == axis }
        if (relevant.isEmpty() && thr.isEmpty()) return null
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (s in relevant) for (p in s.data) {
            if (p.value < mn) mn = p.value
            if (p.value > mx) mx = p.value
        }
        for (t in thr) {
            if (t.value < mn) mn = t.value
            if (t.value > mx) mx = t.value
        }
        if (!mn.isFinite() || !mx.isFinite()) return null
        val pad = ((mx - mn) * 0.1f).coerceAtLeast(0.5f)
        return AxisBounds(mn - pad, mx + pad)
    }
    val left = boundsFor(NeonAxis.LEFT) ?: AxisBounds(-1f, 1f)
    val right = boundsFor(NeonAxis.RIGHT)
    return ChartBounds(left, right)
}

// ============================================================
// Drawing
// ============================================================

/**
 * Отступы под подписи осей. PAD_LEFT=56f даёт ~5 знаков (например "-1100"
 * с минусом) без обрезания; меньше — лейблы накладываются на сетку.
 */
private const val PAD_LEFT = 56f
private const val PAD_RIGHT_BASE = 12f
private const val PAD_RIGHT_WITH_AXIS = 56f
private const val PAD_TOP = 24f
private const val PAD_BOTTOM = 24f

private fun DrawScope.drawNeonChart(
    series: List<NeonSeries>,
    bounds: ChartBounds,
    config: NeonChartConfig,
    firstT: Long,
    lastT: Long,
) {
    val padR = if (bounds.right != null) PAD_RIGHT_WITH_AXIS else PAD_RIGHT_BASE
    val plotL = PAD_LEFT
    val plotR = size.width - padR
    val plotT = PAD_TOP
    val plotB = size.height - PAD_BOTTOM
    val plotW = plotR - plotL
    val plotH = plotB - plotT
    if (plotW <= 0 || plotH <= 0) return

    val tRange = (lastT - firstT).coerceAtLeast(1L)
    fun xPx(t: Long) = plotL + plotW * (t - firstT).toFloat() / tRange
    fun yPx(value: Float, ab: AxisBounds) = plotB - plotH * (value - ab.yMin) / ab.range

    // 1. Grid: 5 horizontal divisions, 6 vertical.
    val hDiv = 5
    val vDiv = 6
    for (i in 0..hDiv) {
        val y = plotT + plotH * i / hDiv
        val color = if (i == 0 || i == hDiv) NeonTheme.gridMajor else NeonTheme.gridMinor
        drawLine(color, Offset(plotL, y), Offset(plotR, y), strokeWidth = 0.5f)
    }
    for (i in 0..vDiv) {
        val x = plotL + plotW * i / vDiv
        val color = if (i == 0 || i == vDiv) NeonTheme.gridMajor else NeonTheme.gridMinor
        drawLine(color, Offset(x, plotT), Offset(x, plotB), strokeWidth = 0.5f)
    }

    // 2. Y-axis labels (left).
    drawAxisLabelsY(bounds.left, plotL - 4f, plotT, plotB, hDiv, right = false)
    bounds.right?.let { drawAxisLabelsY(it, plotR + 4f, plotT, plotB, hDiv, right = true) }

    // 3. X-axis labels (4 ticks).
    val seconds = (lastT - firstT) / 1000f
    val useDecimal = seconds < 10f
    for (i in 0..vDiv) {
        val x = plotL + plotW * i / vDiv
        val t = firstT + tRange * i / vDiv
        val sec = (t - firstT) / 1000f
        val txt = if (useDecimal) "%.1fs".format(Locale.ROOT, sec) else "${sec.toInt()}s"
        drawText(txt, x, plotB + 14f, alignCenter = true)
    }

    // 4. Per-series drawing (envelope → sigma → line → current point).
    for (s in series) {
        val ab = when (s.axis) {
            NeonAxis.LEFT -> bounds.left
            NeonAxis.RIGHT -> bounds.right ?: bounds.left
        }
        if (config.showEnvelope) drawEnvelope(s, ab, ::xPx, ::yPx, plotT, plotB, config.envelopeWindowPoints)
        if (config.showSigma) drawSigma(s, ab, ::xPx, ::yPx, plotT, plotB, config.envelopeWindowPoints)
        drawSeriesLine(s, ab, firstT, lastT, ::xPx, ::yPx)
        drawCurrentPoint(s, ab, ::xPx, ::yPx)
    }

    // 5. Threshold lines.
    for (thr in config.thresholds) {
        val ab = when (thr.axis) {
            NeonAxis.LEFT -> bounds.left
            NeonAxis.RIGHT -> bounds.right ?: continue
        }
        val y = yPx(thr.value, ab)
        if (y in plotT..plotB) {
            drawLine(
                thr.color.copy(alpha = 0.7f),
                Offset(plotL, y), Offset(plotR, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            drawText("⚠ ${thr.label}", plotR - 4f, y - 4f, alignCenter = false, color = thr.color)
        }
    }
}

private fun DrawScope.drawAxisLabelsY(
    ab: AxisBounds, xPx: Float, top: Float, bottom: Float,
    divisions: Int, right: Boolean
) {
    for (i in 0..divisions) {
        val frac = i.toFloat() / divisions
        val v = ab.yMax - frac * ab.range
        val y = top + (bottom - top) * frac
        // Левая ось: метка ВПРАВО от xPx=PAD_LEFT-4, выровнена по правому
        // краю (текст растёт справа-налево). Правая ось: метка ВПРАВО от
        // xPx=plotR+4, выровнена по левому краю (текст растёт слева-направо).
        drawText(formatTick(v), xPx, y + 3f, alignCenter = false, alignRight = !right)
    }
}

private fun formatTick(v: Float): String = when {
    v.isNaN() || !v.isFinite() -> "—"
    abs(v) >= 100f -> "%.0f".format(Locale.ROOT, v)
    abs(v) >= 10f -> "%.1f".format(Locale.ROOT, v)
    else -> "%.2f".format(Locale.ROOT, v)
}

/**
 * Возвращает [firstT, lastT] окно длиной ровно 2 периода доминирующей
 * частоты, или null если периодичность не обнаружена (амплитуда пика ниже
 * threshold).
 *
 * Алгоритм:
 *   1. FFT 64 последних отсчётов с окном Ханна (внутри [Fft]).
 *   2. Поиск bin с максимальной амплитудой (skip DC).
 *   3. T = N / (k * fs), где N=64, k=bin, fs=sampleRateHz.
 *   4. Окно = 2*T*1000 ms, выровнено к концу данных.
 *
 * Если максимум слишком слабый (<10% от RMS) — считаем что периодики нет.
 */
private fun detectPhaseLockWindow(
    data: List<TimedPoint>,
    sampleRateHz: Float,
    fallbackFirst: Long,
    lastT: Long,
): Pair<Long, Long>? {
    val fftSize = 64
    if (data.size < fftSize) return null
    val arr = FloatArray(fftSize)
    val from = data.size - fftSize
    for (i in 0 until fftSize) arr[i] = data[from + i].value
    val spectrum = Fft.amplitudeSpectrum(arr)
    var peakBin = 1
    var peakAmp = 0f
    var sumAmp = 0f
    for (i in 1 until spectrum.size) {
        if (spectrum[i] > peakAmp) { peakAmp = spectrum[i]; peakBin = i }
        sumAmp += spectrum[i]
    }
    val avgAmp = sumAmp / (spectrum.size - 1)
    // Heuristic: пик должен быть хотя бы в 3 раза выше среднего — иначе
    // это шум, а не периодика.
    if (peakAmp < avgAmp * 3f || peakBin < 1) return null
    val periodSec = fftSize / (peakBin * sampleRateHz)
    if (periodSec <= 0f || periodSec.isNaN()) return null
    val windowMs = (2 * periodSec * 1000).toLong()
    val first = (lastT - windowMs).coerceAtLeast(fallbackFirst)
    return if (lastT > first) first to lastT else null
}

/**
 * Cached Paint instances — раньше создавались каждый drawSeriesLine call
 * (3 чарта × 3 серии × 2 paint × 10Hz = 180 Paint/сек + 90 BlurMaskFilter).
 * BlurMaskFilter — native alloc, особенно дорогой. Создаются один раз
 * при загрузке класса, на каждой отрисовке только цвет меняется.
 */
private val haloPaint = Paint().apply {
    style = androidx.compose.ui.graphics.PaintingStyle.Stroke
    strokeWidth = 6f
    strokeCap = StrokeCap.Round
    asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    isAntiAlias = true
}
private val corePaint = Paint().apply {
    style = androidx.compose.ui.graphics.PaintingStyle.Stroke
    strokeWidth = 2f
    strokeCap = StrokeCap.Round
    isAntiAlias = true
}

/**
 * Линия с glow-эффектом: 2 прохода Paint — wide blurred halo + thin solid core.
 *
 * Phase-lock-clip: до построения path урезаем data до окна firstT..lastT.
 * Иначе path тянется от первой точки series.data (timestamp << firstT) до
 * последней — xPx даёт отрицательные/большие значения, glow blur может
 * цеплять видимую область чарта артефактами.
 */
private fun DrawScope.drawSeriesLine(
    s: NeonSeries,
    ab: AxisBounds,
    firstT: Long, lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
) {
    if (s.data.size < 2) return
    val path = buildSmoothPath(s.data, firstT, lastT, xPx, yPx, ab)

    drawIntoCanvas { canvas ->
        haloPaint.color = s.color.glow(0.4f)
        canvas.drawPath(path, haloPaint)
        corePaint.color = s.color
        canvas.drawPath(path, corePaint)
    }
}

/**
 * Catmull-Rom-подобное сглаживание через quadraticBezierTo с control в
 * текущей точке и end в середине следующего сегмента.
 *
 * Раньше control был равен prev (предыдущей точке) → bezier вырождалась
 * в прямую (control НА линии start→end), сглаживания не было.
 *
 * Path clip по firstT..lastT — пропускаем точки вне окна (phase-lock).
 */
private fun buildSmoothPath(
    data: List<TimedPoint>,
    firstT: Long, lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    ab: AxisBounds,
): Path {
    val path = Path()
    // Найти диапазон точек попадающих в окно. Точки до firstT не рисуем,
    // но первую "слева от окна" оставляем как seed для непрерывной линии
    // от левого края canvas (иначе линия "обрывается" перед окном).
    val n = data.size
    if (n == 0) return path
    var startIdx = 0
    while (startIdx < n - 1 && data[startIdx + 1].t < firstT) startIdx++
    var endIdx = n - 1
    while (endIdx > 0 && data[endIdx - 1].t > lastT) endIdx--
    if (startIdx >= endIdx) return path

    val first = data[startIdx]
    path.moveTo(xPx(first.t), yPx(first.value, ab))
    // Quadratic через середины: control=точка i, end=midpoint(i, i+1).
    // Это даёт плавную кривую через все точки данных (Catmull-Rom-like).
    for (i in startIdx + 1 until endIdx) {
        val p = data[i]
        val pn = data[i + 1]
        val mx = (xPx(p.t) + xPx(pn.t)) / 2f
        val my = (yPx(p.value, ab) + yPx(pn.value, ab)) / 2f
        path.quadraticBezierTo(xPx(p.t), yPx(p.value, ab), mx, my)
    }
    // Финальный сегмент — прямая к последней точке.
    val last = data[endIdx]
    path.lineTo(xPx(last.t), yPx(last.value, ab))
    return path
}

private fun DrawScope.drawCurrentPoint(
    s: NeonSeries,
    ab: AxisBounds,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
) {
    val last = s.data.lastOrNull() ?: return
    val x = xPx(last.t); val y = yPx(last.value, ab)
    drawCircle(s.color.copy(alpha = 0.3f), radius = 8f, center = Offset(x, y))
    drawCircle(s.color, radius = 4f, center = Offset(x, y))
}

// ============================================================
// Envelope band (min/max rolling)
// ============================================================

private fun DrawScope.drawEnvelope(
    s: NeonSeries,
    ab: AxisBounds,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    plotT: Float,
    plotB: Float,
    window: Int,
) {
    val data = s.data
    val n = data.size
    if (n < 2) return
    val pathTop = Path()
    val pathBot = Path()
    var started = false
    for (i in 0 until n) {
        val lo = max(0, i - window / 2)
        val hi = min(n - 1, i + window / 2)
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (j in lo..hi) {
            val v = data[j].value
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val x = xPx(data[i].t)
        val yTop = yPx(mx, ab).coerceIn(plotT, plotB)
        val yBot = yPx(mn, ab).coerceIn(plotT, plotB)
        if (!started) {
            pathTop.moveTo(x, yTop)
            pathBot.moveTo(x, yBot)
            started = true
        } else {
            pathTop.lineTo(x, yTop)
            pathBot.lineTo(x, yBot)
        }
    }
    // Замкнуть band: top → reverse bottom.
    val band = Path()
    band.addPath(pathTop)
    // pathBot rev — идём в обратную сторону.
    for (i in n - 1 downTo 0) {
        val lo = max(0, i - window / 2)
        val hi = min(n - 1, i + window / 2)
        var mn = Float.POSITIVE_INFINITY
        for (j in lo..hi) if (data[j].value < mn) mn = data[j].value
        band.lineTo(xPx(data[i].t), yPx(mn, ab).coerceIn(plotT, plotB))
    }
    band.close()
    drawPath(band, s.color.copy(alpha = 0.12f))
}

// ============================================================
// ±1σ scatter band
// ============================================================

private fun DrawScope.drawSigma(
    s: NeonSeries,
    ab: AxisBounds,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    plotT: Float,
    plotB: Float,
    window: Int,
) {
    val data = s.data
    val n = data.size
    if (n < 3) return
    val pts = ArrayList<Offset>(n * 2)
    for (i in 0 until n) {
        val lo = max(0, i - window / 2)
        val hi = min(n - 1, i + window / 2)
        var sum = 0.0; var sumSq = 0.0; var cnt = 0
        for (j in lo..hi) { val v = data[j].value; sum += v; sumSq += v.toDouble() * v; cnt++ }
        val mean = sum / cnt
        val variance = (sumSq / cnt - mean * mean).coerceAtLeast(0.0)
        val sigma = kotlin.math.sqrt(variance).toFloat()
        if (sigma <= 0f) continue
        val x = xPx(data[i].t)
        val yMin = yPx((mean + sigma).toFloat(), ab).coerceIn(plotT, plotB)
        val yMax = yPx((mean - sigma).toFloat(), ab).coerceIn(plotT, plotB)
        pts.add(Offset(x, yMin)); pts.add(Offset(x, yMax))
    }
    if (pts.isEmpty()) return
    drawPoints(
        points = pts,
        pointMode = PointMode.Lines,
        color = s.color.copy(alpha = 0.18f),
        strokeWidth = 1f,
    )
}

// ============================================================
// Helpers
// ============================================================

private fun DrawScope.drawText(
    text: String, x: Float, y: Float,
    alignCenter: Boolean = false,
    alignRight: Boolean = false,
    color: Color = NeonTheme.axisText,
    sizePx: Float = 10f * density,
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = sizePx
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val w = paint.measureText(text)
        val drawX = when {
            alignCenter -> x - w / 2f
            alignRight -> x - w
            else -> x
        }
        canvas.nativeCanvas.drawText(text, drawX, y, paint)
    }
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

// ============================================================
// Legend
// ============================================================

@Composable
private fun LegendRow(
    items: List<Pair<String, Color>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, color) ->
            // Цветной "●" в строке заменяет отдельный кружок-Canvas — проще,
            // меньше layout-логики, выглядит одинаково.
            Text(
                "● $label",
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ============================================================
// Crosshair overlay (общий sync с CrosshairBus)
// ============================================================

@Composable
private fun CrosshairOverlay(
    bus: CrosshairBus,
    firstT: Long,
    lastT: Long,
    series: List<NeonSeries>,
) {
    val c1 = bus.selectedT
    val c2 = bus.secondT
    if (c1 == null && c2 == null) return
    if (lastT <= firstT) return

    val padR = if (series.any { it.axis == NeonAxis.RIGHT }) PAD_RIGHT_WITH_AXIS else PAD_RIGHT_BASE
    Canvas(Modifier.fillMaxSize()) {
        val plotL = PAD_LEFT; val plotR = size.width - padR
        // Cursor 1 — белый.
        c1?.takeIf { it in firstT..lastT }?.let { t ->
            val x = plotL + (plotR - plotL) * (t - firstT).toFloat() / (lastT - firstT)
            drawLine(
                NeonTheme.crosshair.copy(alpha = 0.7f),
                Offset(x, PAD_TOP), Offset(x, size.height - PAD_BOTTOM),
                strokeWidth = 1.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }
        // Cursor 2 — жёлтый (cyan был бы конфликтен с series).
        c2?.takeIf { it in firstT..lastT }?.let { t ->
            val x = plotL + (plotR - plotL) * (t - firstT).toFloat() / (lastT - firstT)
            drawLine(
                Color(0xFFFFEB3B).copy(alpha = 0.8f),
                Offset(x, PAD_TOP), Offset(x, size.height - PAD_BOTTOM),
                strokeWidth = 1.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }
    }

    // Bubble сверху-справа: если 2 курсора — показываем Δ; иначе — значения.
    val text = buildString {
        if (c1 != null && c2 != null) {
            val dtSec = kotlin.math.abs(c2 - c1) / 1000f
            append("Δt=%.2fs".format(Locale.ROOT, dtSec))
            for (s in series) {
                val v1 = findNearest(s.data, c1)?.value
                val v2 = findNearest(s.data, c2)?.value
                if (v1 != null && v2 != null) {
                    append(" · Δ${s.label}=%+.2f".format(Locale.ROOT, v2 - v1))
                }
            }
        } else if (c1 != null) {
            for (s in series) {
                val v = findNearest(s.data, c1)?.value ?: continue
                if (length > 0) append(" · ")
                append("${s.label}=%.2f".format(Locale.ROOT, v))
            }
        }
    }
    if (text.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = NeonTheme.bg.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text,
                    color = NeonTheme.axisText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
