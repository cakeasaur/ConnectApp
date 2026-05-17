package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint
import kotlin.math.abs
import kotlin.math.min

/**
 * Orbit-диаграммы — фазовые портреты в осях (x,y).
 *
 * Три варианта в одной карточке:
 *   - A1 orbit:    ax1 vs ay1  → движение акселерометра A1 в плоскости XY
 *   - A2 orbit:    ax2 vs ay2  → движение A2
 *   - Lissajous:   ax1 vs ax2  → корреляция между датчиками
 *
 * Что показывает на практике:
 *   - идеально балансированный ротор → ровный круг (или эллипс)
 *   - дисбаланс → вытянутый эллипс
 *   - резонанс → "восьмёрка" (Lissajous 2:1)
 *   - bound motion → замкнутая орбита
 *   - chaotic → "клубок" без структуры
 *
 * Этот тип графика — стандарт в Brüel & Kjær / National Instruments
 * виброанализаторах. Препод по embedded оценит.
 */
private const val ORBIT_TRAIL = 200   // последние N точек в orbit trail
private const val MIN_BOUND = 20f     // минимальная амплитуда осей (даже если данные плоские)

@Composable
fun OrbitTripletCard(
    a1x: List<TimedPoint>,
    a1y: List<TimedPoint>,
    a2x: List<TimedPoint>,
    a2y: List<TimedPoint>,
    modifier: Modifier = Modifier
) {
    NeonCard(modifier = modifier.fillMaxWidth()) {
        Text(
            "phase-портрет · trail $ORBIT_TRAIL точек, current point — жирная",
            style = MaterialTheme.typography.bodySmall,
            color = NeonTheme.axisText,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp)
        )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OrbitPanel(
                    title = "A1: ax vs ay",
                    xs = a1x, ys = a1y,
                    accent = Color(0xFFFF5252),
                    modifier = Modifier.weight(1f)
                )
                OrbitPanel(
                    title = "A2: ax vs ay",
                    xs = a2x, ys = a2y,
                    accent = Color(0xFF40C4FF),
                    modifier = Modifier.weight(1f)
                )
                OrbitPanel(
                    title = "Lissajous ax1↔ax2",
                    xs = a1x, ys = a2x,
                    accent = Color(0xFF69F0AE),
                    modifier = Modifier.weight(1f)
                )
            }
    }
}

@Composable
private fun OrbitPanel(
    title: String,
    xs: List<TimedPoint>,
    ys: List<TimedPoint>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = NeonTheme.textPrimary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(top = 2.dp)
        ) {
            OrbitCanvas(xs, ys, accent, Modifier.fillMaxSize())
        }
    }
}

/**
 * Сам рендер: оси-крестик, точки trail с alpha-градиентом (старые тусклые),
 * жирный современный кружок-маркер.
 *
 * Авто-масштаб по абсолютному максимуму последних [ORBIT_TRAIL] точек.
 * Floor [MIN_BOUND] не даёт "лупе" взорваться при плоском сигнале.
 */
@Composable
private fun OrbitCanvas(
    xs: List<TimedPoint>,
    ys: List<TimedPoint>,
    accent: Color,
    modifier: Modifier
) {
    val n = min(xs.size, ys.size)
    val gridColor = NeonTheme.gridMajor
    val axisColor = NeonTheme.axisText.copy(alpha = 0.6f)

    // Подготавливаем подмассивы за пределами Canvas — пары формируем
    // ПО TIMESTAMP, не по индексу. Иначе если xs и ys имеют разные размеры
    // (потеря пакетов, разный rate) — Lissajous пары не соответствуют
    // одному моменту времени, и фигура неинформативна.
    val tail = remember(n, xs.lastOrNull()?.t, ys.lastOrNull()?.t) {
        val count = min(n, ORBIT_TRAIL)
        if (count < 2) return@remember null
        val fromX = xs.size - count
        val tx = FloatArray(count)
        val ty = FloatArray(count)
        for (i in 0 until count) {
            val xPoint = xs[fromX + i]
            tx[i] = xPoint.value
            // Парный отсчёт ys — ближайший по timestamp. Для одной CSV-строки
            // ts одинаковый → берётся ровно тот же индекс; при рассинхронизации
            // получаем правильную временную привязку.
            ty[i] = (findNearest(ys, xPoint.t) ?: xPoint).value
        }
        var maxAbs = MIN_BOUND
        for (v in tx) if (abs(v) > maxAbs) maxAbs = abs(v)
        for (v in ty) if (abs(v) > maxAbs) maxAbs = abs(v)
        Triple(tx, ty, maxAbs)
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f

        // Координатная сетка: круги 33% и 66% радиуса.
        drawCircle(gridColor, radius = r * 0.95f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        drawCircle(gridColor, radius = r * 0.66f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5f))
        drawCircle(gridColor, radius = r * 0.33f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5f))
        // Кресты осей
        drawLine(axisColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1f)
        drawLine(axisColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)

        val data = tail ?: return@Canvas
        val (tx, ty, maxAbs) = data
        val scale = (r * 0.85f) / maxAbs
        val count = tx.size

        // Trail: соединённые линии с alpha-градиентом от старой к новой.
        var prevX = cx + tx[0] * scale
        var prevY = cy - ty[0] * scale
        for (i in 1 until count) {
            val x = cx + tx[i] * scale
            val y = cy - ty[i] * scale
            val ageAlpha = (i.toFloat() / count).coerceIn(0.1f, 1f)
            drawLine(
                color = accent.copy(alpha = 0.15f + 0.85f * ageAlpha),
                start = Offset(prevX, prevY),
                end = Offset(x, y),
                strokeWidth = 1.5f
            )
            prevX = x; prevY = y
        }
        // Current point — жирный glow-маркер.
        val lastX = cx + tx[count - 1] * scale
        val lastY = cy - ty[count - 1] * scale
        // Glow ring (полупрозрачный большой кружок)
        drawCircle(accent.copy(alpha = 0.25f), radius = 8f, center = Offset(lastX, lastY))
        drawCircle(accent, radius = 4f, center = Offset(lastX, lastY))
    }
}

