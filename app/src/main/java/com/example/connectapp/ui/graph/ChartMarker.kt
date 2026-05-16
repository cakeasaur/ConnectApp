package com.example.connectapp.ui.graph

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.compose.component.marker.markerComponent
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.core.component.shape.DashedShape
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.marker.Marker
import com.patrykandpatrick.vico.core.marker.MarkerLabelFormatter
import java.util.Locale

/**
 * Marker для Vico 1.14 — лейбл с координатами точки, появляющийся при тапе
 * (и скрабе) по графику.
 *
 * Состоит из трёх компонент Vico (по API 1.14):
 *   label     — TextComponent с фоном (pill-shape).
 *   indicator — точка-маркер на выделенной entry.
 *   guideline — вертикальная dashed-линия от метки до оси X.
 *
 * Свой [MarkerLabelFormatter] заменяет дефолтный "0.42 / 25.43" на
 * "12s · 25.43" — X у нас секунды от начала записи, выводим как "Xs".
 */
@Composable
fun rememberChartMarker(): Marker {
    val labelBg = shapeComponent(
        shape = Shapes.roundedCornerShape(allPercent = 25),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
    val label = textComponent(
        color = MaterialTheme.colorScheme.onSurface,
        background = labelBg,
        padding = dimensionsOf(horizontal = 8.dp, vertical = 4.dp),
        typeface = Typeface.MONOSPACE,
    )
    val indicator = shapeComponent(
        shape = Shapes.pillShape,
        color = MaterialTheme.colorScheme.primary,
    )
    val guideline = lineComponent(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        thickness = 1.dp,
        shape = remember { DashedShape(Shapes.rectShape, dashLengthDp = 4f, gapLengthDp = 4f) },
    )
    val marker = markerComponent(label = label, indicator = indicator, guideline = guideline)
    // labelFormatter — мутируемое поле MarkerComponent. Сетим один раз
    // через remember-then-set (SideEffect не нужен — поле read-only после установки).
    remember(marker) {
        marker.labelFormatter = MarkerLabelFormatter { entries, _ ->
            // entries — список выделенных точек (по одной на каждую серию,
            // если их несколько в чарте). Берём первую — её x/y покажем.
            val first = entries.firstOrNull() ?: return@MarkerLabelFormatter ""
            val x = first.entry.x
            val y = first.entry.y
            String.format(Locale.ROOT, "%.0fs · %.2f", x, y)
        }
        marker
    }
    return marker
}
