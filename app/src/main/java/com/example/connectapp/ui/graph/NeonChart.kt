package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.Fft
import com.example.connectapp.utils.PerfTrace
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
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
    /**
     * Подписи оси X абсолютным временем (HH:mm:ss) вместо относительных секунд.
     * Нужно для сопоставления выбросов с журналом событий RRD по времени.
     */
    val absoluteTime: Boolean = false,
    /** Помечать точку максимального |значения| в окне колечком и подписью. */
    val showPeaks: Boolean = false,
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
    zoom: ZoomBus? = null,
    crosshair: CrosshairBus? = null,
) {
    val nonEmpty = seriesList.filter { it.data.isNotEmpty() }
    // Density-aware padding: захватываем здесь чтобы использовать в pointerInput
    // (там нет DrawScope, поэтому density нужен из LocalDensity).
    val localDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val padAxisPx = (localDensity * 32f).coerceAtLeast(PAD_LEFT_MIN)
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

    // Accessibility: TalkBack ничего не видит в Canvas-чарте. Собираем
    // текстовую сводку (последнее значение каждой серии + min/max) и
    // вешаем её contentDescription'ом на корневой Box.
    //
    // Ключ remember = (rawLastT, nonEmpty.size). rawLastT меняется на каждом
    // новом отсчёте — этого достаточно, чтобы детектить append/replace.
    // Раньше использовали sumOf { it.data.size } — O(N серий) на каждый
    // recompose, при равных rawLastT всё равно ничего не пересчитывали бы;
    // size серий ≤ 3 — этот ключ почти константа, но даёт стабильный
    // re-eval при добавлении/удалении серии (toggle ax/ay/az).
    val a11y = remember(rawLastT, nonEmpty.size) {
        nonEmpty.joinToString("; ") { s ->
            val values = s.data.map { it.value }
            val last = values.last()
            val mn = values.min()
            val mx = values.max()
            "${s.label} ${"%.2f".format(last)}, min ${"%.2f".format(mn)}, max ${"%.2f".format(mx)}"
        }
    }

    // Zoom: сначала сужаем диапазон по ZoomBus (pinch/pan), потом
    // опционально ещё раз по phaseLock. Когда zoom активен — phaseLock
    // пропускаем: пользователь уже выбрал нужное окно руками.
    val baseRange = zoom?.applyTo(rawFirstT, rawLastT) ?: (rawFirstT to rawLastT)
    val (firstT, lastT) = if (config.phaseLock && zoom?.isZoomed != true) {
        detectPhaseLockWindow(nonEmpty[0].data, config.sampleRateHz, baseRange.first, baseRange.second)
            ?: baseRange
    } else baseRange

    // bounds считаются по ВСЕМ данным серий (не по phase-lock окну) — иначе
    // Y-масштаб скачет на каждом новом отсчёте. target пересчитываем напрямую
    // каждый кадр (O(N) дёшево), а settle() демпфирует переключение границ
    // через remember-холдер (см. AxisHold) — без залипания старого bounds.
    val axisHold = remember { AxisHold() }
    val raw = rawRanges(nonEmpty, config.thresholds)
    val settledLeft = niceAxis(raw.left.first, raw.left.second)
        .settle(axisHold.left).also { axisHold.left = it }
    // Правая ось привязана к числу делений (settled) левой → общая сетка, обе nice.
    val settledRight = raw.right?.let { niceAxisFixed(it.first, it.second, settledLeft.count) }
    val bounds = ChartBounds(settledLeft, settledRight)

    // Ширина чарта в пикселях. NaN = ещё не обмерян (до первого onSizeChanged).
    // NaN-guard в transformableState исключает неверный скачок при первом свайпе
    // когда реальная ширина ещё неизвестна.
    val chartWidthRef = remember { floatArrayOf(Float.NaN) }
    val transformableState = rememberTransformableState { scaleChange, panChange, _ ->
        if (zoom != null) {
            zoom.scaleX = (zoom.scaleX * scaleChange).coerceIn(1f, ZoomBus.MAX_SCALE)
            if (zoom.isZoomed) {
                val w = chartWidthRef[0]
                if (!w.isNaN() && w > 0f) {
                    zoom.offsetFraction -= panChange.x / w / zoom.scaleX
                }
            }
        }
    }
    // rememberUpdatedState: pointerInput-лямбда не рестартует при новых данных
    // (ключи не меняются), поэтому firstT/lastT захватывались бы как stale.
    // State-объект захватывается по ссылке → лямбда читает актуальное значение.
    val currentFirstT by rememberUpdatedState(firstT)
    val currentLastT by rememberUpdatedState(lastT)

    // Reusable Path/Band — выделяется один раз на жизнь Composable.
    // Каждый кадр reuse: .reset() в начале drawSeriesLine, аккумуляция, draw.
    // Раньше Path() new каждый draw × 3 series × 30 fps = 90 native-alloc/сек.
    val linePath = remember { Path() }
    val bandPath = remember { Path() }
    val fillPath = remember { Path() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NeonTheme.bg)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .onSizeChanged { chartWidthRef[0] = it.width.toFloat() }
            // transformable: enabled только если zoom передан — иначе модификатор
            // не конкурирует с вертикальным скроллом родителя.
            .transformable(transformableState, lockRotationOnZoomPan = true, enabled = zoom != null)
            // Tap → crosshair, DoubleTap → сброс зума.
            // Объединяем оба случая в один pointerInput чтобы не конфликтовали.
            .let { m ->
                if (crosshair == null && zoom == null) m
                else m.pointerInput(crosshair, zoom) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (crosshair == null || currentLastT <= currentFirstT) return@detectTapGestures
                            val padR = if (nonEmpty.any { it.axis == NeonAxis.RIGHT }) padAxisPx else PAD_RIGHT_BASE
                            val plotL = padAxisPx
                            val plotR = size.width - padR
                            if (offset.x < plotL || offset.x > plotR) return@detectTapGestures
                            val frac = ((offset.x - plotL) / (plotR - plotL)).coerceIn(0f, 1f)
                            crosshair.tap(currentFirstT + (frac * (currentLastT - currentFirstT)).toLong())
                        }
                    )
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            PerfTrace.measure("chart.draw") {
                drawNeonChart(nonEmpty, bounds, config, firstT, lastT, linePath, bandPath, fillPath)
            }
        }
        // Легенда сверху-слева: цвет, метка, текущее значение и пометка оси.
        LegendRow(
            nonEmpty.map {
                LegendItem(
                    label = it.label,
                    color = it.color,
                    value = it.data.lastOrNull()?.value,
                    rightAxis = it.axis == NeonAxis.RIGHT,
                )
            },
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        )
        // Индикатор зума снизу-справа: "×2.5". Двойной тап сбрасывает.
        if (zoom != null && zoom.isZoomed) {
            Text(
                "×${"%.1f".format(Locale.ROOT, zoom.scaleX)}",
                color = NeonTheme.accent,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(NeonTheme.bg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
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

private data class AxisBounds(
    val yMin: Float,
    val yMax: Float,
    val step: Float = 0f,
    val count: Int = Y_DIVISIONS,
) {
    val range: Float get() = (yMax - yMin).coerceAtLeast(1e-6f)
}

private data class ChartBounds(val left: AxisBounds, val right: AxisBounds?)

private const val Y_DIVISIONS = 5

/**
 * Сырые (min,max) по каждой оси — БЕЗ nice-округления. Округление и гистерезис
 * делаем в @Composable (нужно состояние remember). Threshold-значения включены
 * в диапазон, чтобы ось их накрыла.
 */
private data class RawRanges(val left: Pair<Float, Float>, val right: Pair<Float, Float>?)

private fun rawRanges(series: List<NeonSeries>, thresholds: List<NeonThreshold>): RawRanges {
    fun rangeFor(axis: NeonAxis): Pair<Float, Float>? {
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
        return mn to mx
    }
    return RawRanges(rangeFor(NeonAxis.LEFT) ?: (-1f to 1f), rangeFor(NeonAxis.RIGHT))
}

/**
 * «Красивые» границы оси по Хекберту: step округлён к 1/2/5·10ⁿ, границы —
 * floor/ceil к шагу. Число делений ПЕРЕМЕННОЕ — это даёт тугую подгонку без
 * переразмаха (фикс-count раньше растягивал ±477 в диапазон −500…2000).
 * Подписи вида 23/24/25 вместо сырых 23.47, и границы меняются только при
 * переходе через nice-бэнд, а не на каждый отсчёт.
 */
private fun niceAxis(min: Float, max: Float): AxisBounds {
    var lo = min
    var hi = max
    if (!lo.isFinite() || !hi.isFinite()) return AxisBounds(-1f, 1f, 0.4f, 5)
    if (hi - lo < 1e-6f) {
        // Плоский сигнал — синтетический диапазон вокруг значения, иначе step=0.
        val c = (lo + hi) / 2f
        val d = if (abs(c) > 1f) abs(c) * 0.05f else 0.5f
        lo = c - d; hi = c + d
    }
    val target = 5
    val niceRange = niceNum(hi - lo, round = false).coerceAtLeast(1e-9f)
    val step = niceNum(niceRange / (target - 1), round = true).coerceAtLeast(1e-9f)
    val niceMin = floor(lo / step) * step
    val niceMax = ceil(hi / step) * step
    val count = Math.round((niceMax - niceMin) / step).coerceAtLeast(1)
    return AxisBounds(niceMin, niceMax, step, count)
}

/**
 * Nice-ось с ФИКСИРОВАННЫМ числом делений — для правой оси, чтобы её сетка
 * совпала с левой (count берём от левой). Шаг растим, пока [count] делений не
 * накроют диапазон.
 */
private fun niceAxisFixed(min: Float, max: Float, count: Int): AxisBounds {
    var lo = min
    var hi = max
    if (!lo.isFinite() || !hi.isFinite()) return AxisBounds(-1f, 1f, 0.4f, count)
    if (hi - lo < 1e-6f) {
        val c = (lo + hi) / 2f
        val d = if (abs(c) > 1f) abs(c) * 0.05f else 0.5f
        lo = c - d; hi = c + d
    }
    var step = niceNum((hi - lo) / count, round = true).coerceAtLeast(1e-9f)
    var niceMin = floor(lo / step) * step
    var niceMax = niceMin + count * step
    var guard = 0
    while (niceMax < hi && guard++ < 8) {
        step = niceNum(step * 1.5f, round = true)
        niceMin = floor(lo / step) * step
        niceMax = niceMin + count * step
    }
    return AxisBounds(niceMin, niceMax, step, count)
}

/** Округление x к ближайшему «приятному» числу (1/2/5·10ⁿ). */
private fun niceNum(x: Float, round: Boolean): Float {
    if (x <= 0f) return 1f
    val exp = floor(log10(x.toDouble())).toInt()
    val pow = Math.pow(10.0, exp.toDouble()).toFloat()
    val f = x / pow
    val nf = if (round) {
        when { f < 1.5f -> 1f; f < 3f -> 2f; f < 7f -> 5f; else -> 10f }
    } else {
        when { f <= 1f -> 1f; f <= 2f -> 2f; f <= 5f -> 5f; else -> 10f }
    }
    return nf * pow
}

/**
 * Анти-дёрганье оси: расширяемся сразу (новый пик не должен клиппиться), а
 * сужаемся только когда данные ушли вглубь ≥2 делений. Иначе ось «прыгает»
 * между соседними nice-бэндами, когда максимум колеблется у их границы.
 *
 * Состояние держим в [AxisHold] (remember на уровне NeonChart): target всё
 * равно пересчитывается каждый кадр, тут лишь демпфируем переключение — поэтому
 * залипания, из-за которого раньше убрали remember у bounds, не возникает.
 */
private class AxisHold {
    var left: AxisBounds? = null
}

private fun AxisBounds.settle(prev: AxisBounds?): AxisBounds {
    if (prev == null || prev.step <= 0f) return this
    val grew = yMax > prev.yMax + 1e-4f || yMin < prev.yMin - 1e-4f
    if (grew) return this
    val margin = 2f * prev.step
    val shrank = yMax <= prev.yMax - margin || yMin >= prev.yMin + margin
    return if (shrank) this else prev
}

// ============================================================
// Drawing
// ============================================================

/**
 * Отступы под подписи осей. Значения в ПИКСЕЛЯХ Canvas (не в dp).
 * PAD_LEFT/PAD_RIGHT_WITH_AXIS вычисляются динамически в DrawScope
 * на основе density, поэтому константы здесь — fallback-минимумы.
 *
 * Формула: density * 10sp * 5.5 chars * ~0.58 char-width-ratio ≈ density * 32f.
 * Бюджет 5.5 символов — чтобы влезала отрицательная метка со знаком ("-0.50"),
 * иначе минус (крайний левый символ) подрезался clipRect у границы канваса.
 */
private const val PAD_LEFT_MIN = 90f   // fallback если density < 2
private const val PAD_RIGHT_BASE = 12f

// Формат меток оси X в режиме абсолютного времени (config.absoluteTime).
private val absTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private const val PAD_RIGHT_AXIS_MIN = 72f
private const val PAD_TOP = 24f
private const val PAD_BOTTOM = 24f

private fun DrawScope.drawNeonChart(
    series: List<NeonSeries>,
    bounds: ChartBounds,
    config: NeonChartConfig,
    firstT: Long,
    lastT: Long,
    linePath: Path,
    bandPath: Path,
    fillPath: Path,
) {
    // Density-aware: 10sp × 5.5 chars × ~0.58 char-width ≈ density*32f.
    // Бюджет 5.5 символов — под отрицательную метку со знаком ("-0.50"),
    // иначе минус (крайний левый символ) обрезался слева.
    val padAxis = (density * 32f).coerceAtLeast(PAD_LEFT_MIN)
    val padR = if (bounds.right != null) padAxis else PAD_RIGHT_BASE
    val plotL = padAxis
    val plotR = size.width - padR
    val plotT = PAD_TOP
    val plotB = size.height - PAD_BOTTOM
    val plotW = plotR - plotL
    val plotH = plotB - plotT
    if (plotW <= 0 || plotH <= 0) return

    val tRange = (lastT - firstT).coerceAtLeast(1L)
    fun xPx(t: Long) = plotL + plotW * (t - firstT).toFloat() / tRange
    fun yPx(value: Float, ab: AxisBounds) = plotB - plotH * (value - ab.yMin) / ab.range

    // 1. Grid: число горизонтальных делений = count левой оси (правая
    // выровнена к нему) — линии ложатся ровно на nice-тики обеих осей.
    val hDiv = bounds.left.count
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

    // 3. X-axis labels.
    if (config.absoluteTime) {
        // Абсолютное время HH:mm:ss. Метки шире относительных секунд, поэтому
        // рисуем только три (начало/середина/конец) с краевым выравниванием —
        // иначе 6 timestamp'ов наезжают друг на друга и клипаются у краёв.
        for (i in intArrayOf(0, vDiv / 2, vDiv)) {
            val t = firstT + tRange * i / vDiv
            val txt = absTimeFmt.format(java.util.Date(t))
            when (i) {
                0 -> drawText(txt, plotL, plotB + 14f)
                vDiv -> drawText(txt, plotR, plotB + 14f, alignRight = true)
                else -> drawText(txt, plotL + plotW * i / vDiv, plotB + 14f, alignCenter = true)
            }
        }
    } else {
        val seconds = (lastT - firstT) / 1000f
        val useDecimal = seconds < 10f
        for (i in 0..vDiv) {
            val x = plotL + plotW * i / vDiv
            val t = firstT + tRange * i / vDiv
            val sec = (t - firstT) / 1000f
            val txt = if (useDecimal) "%.1fs".format(Locale.ROOT, sec) else "${sec.toInt()}s"
            drawText(txt, x, plotB + 14f, alignCenter = true)
        }
    }

    // 4. Per-series drawing (envelope → sigma → line → current point).
    // Если у серии есть threshold той же оси — линия выше порога красится
    // в alert-цвет (через canvas clip, без вторичной интерполяции точек).
    //
    // linePath/bandPath переиспользуются между сериями: каждая drawSeriesLine
    // / drawEnvelope делает .reset() и заполняет заново. Это убирает
    // Path() native-alloc на каждый кадр (раньше 90+ alloc/сек).
    for (s in series) {
        val ab = when (s.axis) {
            NeonAxis.LEFT -> bounds.left
            NeonAxis.RIGHT -> bounds.right ?: bounds.left
        }
        val thr = config.thresholds.firstOrNull { it.axis == s.axis }
        val alertY = thr?.let { yPx(it.value, ab) }
        // Лёгкая градиентная заливка под линией — рисуем ПЕРВОЙ (фоном), чтобы
        // линия/glow и точки легли поверх. Даёт «глубину» без новой логики.
        if (s.data.size >= 2) {
            buildSmoothPath(fillPath, s.data, firstT, lastT, ::xPx, ::yPx, ab, closeBottomY = plotB)
            drawPath(
                fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(s.color.copy(alpha = 0.18f), Color.Transparent),
                    startY = plotT, endY = plotB,
                ),
            )
        }
        if (config.showEnvelope) drawEnvelope(s, ab, firstT, lastT, ::xPx, ::yPx, plotT, plotB, config.envelopeWindowPoints, bandPath)
        if (config.showSigma) drawSigma(s, ab, firstT, lastT, ::xPx, ::yPx, plotT, plotB, config.envelopeWindowPoints)
        drawSeriesLine(s, ab, firstT, lastT, ::xPx, ::yPx, alertY, linePath)
        drawCurrentPoint(s, ab, ::xPx, ::yPx, alertY)
        if (config.showPeaks) drawPeak(s, ab, firstT, lastT, ::xPx, ::yPx, plotT)
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
            // alignRight: текст растёт влево от plotR-4f.
            // clipRect в drawText не даёт вылезти за границу Canvas.
            val textY = (y - 4f).coerceAtLeast(plotT + 10f)
            drawText("⚠ ${thr.label}", plotR - 4f, textY, alignRight = true, color = thr.color)
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
        drawText(formatTickStep(v, ab.step), xPx, y + 3f, alignCenter = false, alignRight = !right)
    }
}

/**
 * Формат nice-тика по величине шага: число знаков после запятой = столько,
 * сколько нужно шагу (step 2 → "24", step 0.5 → "1.5", step 0.05 → "0.05").
 * Околонулевые значения схлопываем в "0", чтобы не показывать "-0.00".
 */
private fun formatTickStep(v: Float, step: Float): String {
    if (step <= 0f) return formatTick(v)
    val decimals = if (step >= 1f) 0 else ceil(-log10(step.toDouble())).toInt().coerceIn(0, 6)
    val vv = if (abs(v) < step * 1e-3f) 0f else v
    return "%.${decimals}f".format(Locale.ROOT, vv)
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
    // Среднее БЕЗ пика — иначе при сильном пике он сам подтягивает
    // avgAmp вверх, и критерий peak>3×avg почти всегда срабатывает.
    // Без peak: avg отражает уровень "не-сигнальных" бинов.
    val otherBinsCount = (spectrum.size - 2).coerceAtLeast(1)
    val avgAmp = (sumAmp - peakAmp) / otherBinsCount
    // Heuristic: пик должен быть хотя бы в 3 раза выше "шума" (среднего
    // непиковых бинов). Иначе это широкополосный шум, а не периодика.
    if (peakAmp < avgAmp * 3f) return null
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
    alertY: Float? = null,   // y-координата порога в пикселях; null = подсветки нет
    path: Path,              // переиспользуемый между сериями/кадрами
) {
    if (s.data.size < 2) return
    PerfTrace.measure("path.build") {
        buildSmoothPath(path, s.data, firstT, lastT, xPx, yPx, ab)
    }

    drawIntoCanvas { canvas ->
        if (alertY == null) {
            haloPaint.color = s.color.glow(0.4f)
            canvas.drawPath(path, haloPaint)
            corePaint.color = s.color
            canvas.drawPath(path, corePaint)
        } else {
            // Двойная отрисовка с canvas clip:
            //   1) область НИЖЕ порога (y > alertY) — родной цвет серии
            //   2) область ВЫШЕ порога (y < alertY) — alert-цвет (красный)
            // Y растёт ВНИЗ → "выше по значению" = меньшее y на экране.
            val nc = canvas.nativeCanvas
            // Нижняя часть
            nc.save()
            nc.clipRect(0f, alertY, size.width, size.height)
            haloPaint.color = s.color.glow(0.4f)
            canvas.drawPath(path, haloPaint)
            corePaint.color = s.color
            canvas.drawPath(path, corePaint)
            nc.restore()
            // Верхняя часть (превышение порога)
            nc.save()
            nc.clipRect(0f, 0f, size.width, alertY)
            haloPaint.color = NeonTheme.alert.glow(0.5f)
            canvas.drawPath(path, haloPaint)
            corePaint.color = NeonTheme.alert
            canvas.drawPath(path, corePaint)
            nc.restore()
        }
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
    path: Path,
    data: List<TimedPoint>,
    firstT: Long, lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    ab: AxisBounds,
    closeBottomY: Float? = null,   // если задано — замыкаем контур вниз для заливки
) {
    // Path передан извне (remember в @Composable) и переиспользуется между
    // кадрами/сериями. reset() очищает внутренние буферы без realloc.
    path.reset()
    // Найти диапазон точек попадающих в окно. Точки до firstT не рисуем,
    // но первую "слева от окна" оставляем как seed для непрерывной линии
    // от левого края canvas (иначе линия "обрывается" перед окном).
    val n = data.size
    if (n == 0) return
    var startIdx = 0
    while (startIdx < n - 1 && data[startIdx + 1].t < firstT) startIdx++
    var endIdx = n - 1
    while (endIdx > 0 && data[endIdx - 1].t > lastT) endIdx--
    if (startIdx >= endIdx) return

    val first = data[startIdx]
    val firstX = xPx(first.t)
    path.moveTo(firstX, yPx(first.value, ab))
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
    val lastX = xPx(last.t)
    path.lineTo(lastX, yPx(last.value, ab))
    // Для заливки замыкаем контур вниз: линия → правый низ → левый низ → старт.
    if (closeBottomY != null) {
        path.lineTo(lastX, closeBottomY)
        path.lineTo(firstX, closeBottomY)
        path.close()
    }
}

/**
 * Аннотация пика: точка максимального |значения| серии в окне — колечко +
 * подпись значения над ней. Для вибрации это самый информативный маркер.
 */
private fun DrawScope.drawPeak(
    s: NeonSeries,
    ab: AxisBounds,
    firstT: Long, lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    plotT: Float,
) {
    var peak: TimedPoint? = null
    var peakAbs = -1f
    for (p in s.data) {
        if (p.t < firstT || p.t > lastT) continue
        val a = abs(p.value)
        if (a > peakAbs) { peakAbs = a; peak = p }
    }
    val pk = peak ?: return
    val x = xPx(pk.t)
    val y = yPx(pk.value, ab)
    drawCircle(s.color, radius = 5f, center = Offset(x, y), style = Stroke(width = 1.5f))
    drawText(formatTick(pk.value), x, (y - 8f).coerceAtLeast(plotT + 9f), alignCenter = true, color = s.color)
}

private fun DrawScope.drawCurrentPoint(
    s: NeonSeries,
    ab: AxisBounds,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    alertY: Float? = null,
) {
    val last = s.data.lastOrNull() ?: return
    val x = xPx(last.t); val y = yPx(last.value, ab)
    // y < alertY = значение выше порога (Y растёт вниз) → красная точка
    val exceeded = alertY != null && y < alertY
    val color = if (exceeded) NeonTheme.alert else s.color
    drawCircle(color.copy(alpha = 0.3f), radius = 8f, center = Offset(x, y))
    drawCircle(color, radius = 4f, center = Offset(x, y))
}

// ============================================================
// Envelope band (min/max rolling)
// ============================================================

/**
 * Min/max envelope с phase-lock-clip и кэшем min/max в массиве.
 *
 * Phase-lock-clip: считаем только точки в окне firstT..lastT. Без этого
 * band тянулся через ВСЕ s.data, давая отрицательные xPx за левым краем.
 *
 * Один проход по окну (раньше было два: первый строил pathTop+pathBot,
 * второй ПЕРЕСЧИТЫВАЛ min заново для замыкания band). Теперь кэшируем
 * min/max в FloatArray и используем при замыкании.
 */
private fun DrawScope.drawEnvelope(
    s: NeonSeries,
    ab: AxisBounds,
    firstT: Long,
    lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    plotT: Float,
    plotB: Float,
    window: Int,
    band: Path,    // переиспользуемый Path — reset() в начале
) {
    val data = s.data
    val n = data.size
    if (n < 2) return

    // Clip к окну [firstT..lastT]. Берём один индекс слева ПРЕД границей
    // (если есть) как seed для непрерывной линии от левого края канваса.
    var startIdx = 0
    while (startIdx < n - 1 && data[startIdx + 1].t < firstT) startIdx++
    var endIdx = n - 1
    while (endIdx > 0 && data[endIdx - 1].t > lastT) endIdx--
    if (startIdx >= endIdx) return

    val len = endIdx - startIdx + 1
    val mins = FloatArray(len)
    val maxs = FloatArray(len)
    // Один проход: считаем min/max по окну вокруг каждой точки.
    for (k in 0 until len) {
        val i = startIdx + k
        val lo = max(startIdx, i - window / 2)
        val hi = min(endIdx, i + window / 2)
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (j in lo..hi) {
            val v = data[j].value
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        mins[k] = mn
        maxs[k] = mx
    }

    band.reset()
    // Top edge — forward.
    band.moveTo(xPx(data[startIdx].t), yPx(maxs[0], ab).coerceIn(plotT, plotB))
    for (k in 1 until len) {
        band.lineTo(xPx(data[startIdx + k].t), yPx(maxs[k], ab).coerceIn(plotT, plotB))
    }
    // Bot edge — reverse, используем закэшированный min.
    for (k in len - 1 downTo 0) {
        band.lineTo(xPx(data[startIdx + k].t), yPx(mins[k], ab).coerceIn(plotT, plotB))
    }
    band.close()
    drawPath(band, s.color.copy(alpha = 0.12f))
}

// ============================================================
// ±1σ scatter band
// ============================================================

/**
 * ±1σ scatter band с phase-lock-clip и стабильной формулой variance.
 *
 * Phase-lock-clip: считаем только точки в окне firstT..lastT.
 *
 * Variance: ДВУХПРОХОДНАЯ формула `Σ(x-μ)² / (n-1)` вместо одно-проходной
 * `Σx²/n - (Σx/n)²`. Последняя страдает catastrophic cancellation на
 * сигналах с малой дисперсией и большим mean (например, температура
 * 25°C ± 0.05°C: Σx² и Σx огромные, разница крошечная — теряется в
 * Float-ариметике). Bessel's correction (n-1) — sample variance,
 * статистически правильно для оценки шума по выборке.
 */
private fun DrawScope.drawSigma(
    s: NeonSeries,
    ab: AxisBounds,
    firstT: Long,
    lastT: Long,
    xPx: (Long) -> Float,
    yPx: (Float, AxisBounds) -> Float,
    plotT: Float,
    plotB: Float,
    window: Int,
) {
    val data = s.data
    val n = data.size
    if (n < 3) return

    var startIdx = 0
    while (startIdx < n - 1 && data[startIdx + 1].t < firstT) startIdx++
    var endIdx = n - 1
    while (endIdx > 0 && data[endIdx - 1].t > lastT) endIdx--
    if (startIdx >= endIdx) return

    // FloatArray для nativeCanvas.drawLines: формат [x0,y0, x1,y1, x2,y2, x3,y3, ...]
    // — каждая пара coords = одна точка, каждая пара точек = одна линия.
    // Это уровень drawPoints(PointMode.Lines), но БЕЗ Offset-аллокаций
    // (раньше len × 2 Offset() objects + ArrayList grow на каждый кадр).
    val maxLen = (endIdx - startIdx + 1) * 4   // x,y × 2 endpoints на каждый i
    val coords = FloatArray(maxLen)
    var coordIdx = 0
    for (i in startIdx..endIdx) {
        val lo = max(startIdx, i - window / 2)
        val hi = min(endIdx, i + window / 2)
        val cnt = hi - lo + 1
        if (cnt < 2) continue
        // Pass 1: mean.
        var sum = 0.0
        for (j in lo..hi) sum += data[j].value
        val mean = sum / cnt
        // Pass 2: Σ(x - μ)² — numerically stable, нет cancellation.
        var sumSqDev = 0.0
        for (j in lo..hi) {
            val d = data[j].value - mean
            sumSqDev += d * d
        }
        // Bessel (n-1): sample variance — корректная оценка по выборке.
        val variance = (sumSqDev / (cnt - 1)).coerceAtLeast(0.0)
        val sigma = kotlin.math.sqrt(variance).toFloat()
        if (sigma <= 0f) continue
        val x = xPx(data[i].t)
        val yMin = yPx((mean + sigma).toFloat(), ab).coerceIn(plotT, plotB)
        val yMax = yPx((mean - sigma).toFloat(), ab).coerceIn(plotT, plotB)
        coords[coordIdx++] = x; coords[coordIdx++] = yMin
        coords[coordIdx++] = x; coords[coordIdx++] = yMax
    }
    if (coordIdx == 0) return
    val sigmaPaint = sigmaPaint()
    sigmaPaint.color = s.color.copy(alpha = 0.18f).toArgb()
    drawIntoCanvas { canvas ->
        // drawLines принимает offset/count в количестве чисел (не пар).
        // coordIdx уже считает использованные слоты — передаём как count.
        canvas.nativeCanvas.drawLines(coords, 0, coordIdx, sigmaPaint)
    }
}

/**
 * Cached scratch Paint для drawSigma — переиспользуется между сериями.
 * Создаётся лениво; цвет/alpha задаются вызывающим перед drawLines.
 */
private val sigmaPaintCache = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.STROKE
    strokeWidth = 1f
    isAntiAlias = true
}
private fun sigmaPaint(): android.graphics.Paint = sigmaPaintCache

// ============================================================
// Helpers
// ============================================================

// Singleton Paint для меток осей / threshold текста.
// Раньше new android.graphics.Paint() на КАЖДЫЙ drawText — это ~20 текстов на
// чарт × 3 чарта × 30 fps = 1800 native-alloc/сек + setupGlyphCache.
// Цвет/размер обновляются перед каждой надписью, остальное (antialias,
// typeface) задаётся один раз при загрузке класса.
private val textPaintCache = android.graphics.Paint().apply {
    isAntiAlias = true
    typeface = android.graphics.Typeface.MONOSPACE
}

private fun DrawScope.drawText(
    text: String, x: Float, y: Float,
    alignCenter: Boolean = false,
    alignRight: Boolean = false,
    color: Color = NeonTheme.axisText,
    sizePx: Float = 10f * density,
) {
    val paint = textPaintCache
    paint.color = color.toArgb()
    paint.textSize = sizePx
    val w = paint.measureText(text)
    val drawX = when {
        alignCenter -> x - w / 2f
        alignRight -> x - w
        else -> x
    }
    drawIntoCanvas { canvas ->
        // nativeCanvas не уважает Compose-clip (.clip(RoundedCornerShape)).
        // Явный clipRect по границам Canvas гарантирует что текст не вылезет
        // ни вправо (крайняя X-метка), ни влево (Y-метки у края), ни вверх/вниз.
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.clipRect(0f, 0f, size.width, size.height)
        canvas.nativeCanvas.drawText(text, drawX, y, paint)
        canvas.nativeCanvas.restore()
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

private data class LegendItem(
    val label: String,
    val color: Color,
    val value: Float?,
    val rightAxis: Boolean,
)

@Composable
private fun LegendRow(
    items: List<LegendItem>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            // "● ax 0.42ᴿ" — цвет, метка, текущее значение, ᴿ для правой оси.
            // Цветной "●" в строке заменяет отдельный кружок-Canvas.
            val v = item.value?.let { " " + formatTick(it) } ?: ""
            val axisMark = if (item.rightAxis) "ᴿ" else ""
            Text(
                "● ${item.label}$v$axisMark",
                color = item.color,
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

    Canvas(Modifier.fillMaxSize()) {
        val padAxis = (density * 32f).coerceAtLeast(PAD_LEFT_MIN)
        val padR = if (series.any { it.axis == NeonAxis.RIGHT }) padAxis else PAD_RIGHT_BASE
        val plotL = padAxis; val plotR = size.width - padR
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
                    .widthIn(max = 260.dp)   // не даём bubble вылезти за край карточки
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
