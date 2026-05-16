package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import java.util.Locale

/**
 * Шина общего состояния sync-crosshair. Тап на любом чарте обновляет
 * timestamp здесь — все чарты подписаны и одновременно отрисовывают
 * вертикальную линию в этом времени.
 *
 * Объект (не StateFlow) хранится в [androidx.compose.runtime.remember] на
 * уровне GraphScreen — это даёт shared mutable state без боксинга Long.
 */
class CrosshairBus {
    /** Текущий выбранный timestamp; null если crosshair скрыт. */
    private val state = androidx.compose.runtime.mutableStateOf<Long?>(null)
    var selectedT: Long?
        get() = state.value
        set(v) { state.value = v }
}

/**
 * Обёртка над Vico-чартом, добавляющая:
 *   - tap-detector → обновляет shared [bus.selectedT]
 *   - overlay Canvas с вертикальной dashed-линией crosshair, если selectedT
 *     попадает в диапазон времени серий
 *   - метку справа сверху с числовыми значениями всех серий на выбранном
 *     моменте (ближайшая точка)
 *
 * Координаты линии — приблизительные: используем полную ширину Box без
 * учёта Vico Y-axis padding (~30dp слева). Сдвиг визуально незаметен
 * (линия dashed и без маркера на самой линии данных). Это компромисс
 * точности ради простоты — программно вытащить chart-bounds из Vico 1.14
 * нельзя.
 *
 * @param seriesData серии в одном чарте: пары (данные, цвет, метка)
 */
@Composable
fun ChartWithCrosshair(
    seriesData: List<Triple<List<TimedPoint>, Color, String>>,
    bus: CrosshairBus,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val nonEmpty = seriesData.filter { it.first.isNotEmpty() }
    Box(modifier = modifier) {
        // Сам Vico-чарт ниже.
        content()
        // Overlay tap-детектор. fillMaxSize — иначе тап в zoom-area Vico
        // конкурирует с pan-gestures Vico (но у нас scroll выключен → ок).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nonEmpty.size) {
                    detectTapGestures { offset ->
                        if (nonEmpty.isEmpty()) return@detectTapGestures
                        val minT = nonEmpty.minOf { it.first.first().t }
                        val maxT = nonEmpty.maxOf { it.first.last().t }
                        if (maxT <= minT) return@detectTapGestures
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        bus.selectedT = minT + (frac * (maxT - minT)).toLong()
                    }
                }
        ) {
            val selT = bus.selectedT
            if (selT != null && nonEmpty.isNotEmpty()) {
                val minT = nonEmpty.minOf { it.first.first().t }
                val maxT = nonEmpty.maxOf { it.first.last().t }
                if (maxT > minT && selT in minT..maxT) {
                    val frac = ((selT - minT).toFloat() / (maxT - minT)).coerceIn(0f, 1f)
                    val lineColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width * frac
                        drawLine(
                            color = lineColor.copy(alpha = 0.7f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                        )
                    }
                    // Bubble с значениями ближайших точек.
                    val values = nonEmpty.mapNotNull { (data, _, label) ->
                        val p = findNearest(data, selT) ?: return@mapNotNull null
                        "$label=%.2f".format(Locale.ROOT, p.value)
                    }.joinToString(" · ")
                    if (values.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                values,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Поиск ближайшей по timestamp точки в отсортированной по времени серии.
 * Бинарный поиск O(log n) — серии до 600 точек, разница vs линейного не
 * критична, но сохраняем привычку.
 */
internal fun findNearest(series: List<TimedPoint>, t: Long): TimedPoint? {
    if (series.isEmpty()) return null
    if (t <= series.first().t) return series.first()
    if (t >= series.last().t) return series.last()
    var lo = 0; var hi = series.size - 1
    while (lo < hi - 1) {
        val mid = (lo + hi) ushr 1
        if (series[mid].t <= t) lo = mid else hi = mid
    }
    // Возвращаем тот из lo/hi, чей timestamp ближе к t.
    return if (t - series[lo].t <= series[hi].t - t) series[lo] else series[hi]
}
