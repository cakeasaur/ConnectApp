package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.connectapp.data.models.TimedPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 3D scatter plot облака точек акселерометра.
 *
 * Реализация: самописная перспективная проекция в Canvas.
 *   - точка (x, y, z) поворачивается вокруг Y (yaw) и X (pitch)
 *   - проецируется по формуле x' = x * focal / (z + dist), y' = y * focal / (z + dist)
 *   - получает экранную координату из центра канваса
 *
 * Без OpenGL / Filament — никаких лишних зависимостей.
 * Жесты:
 *   - перетаскивание одним пальцем → вращение (yaw, pitch)
 *   - pinch двумя пальцами → зум
 */
@Composable
fun Accel3DChart(
    a1: AccelTriple,
    a2: AccelTriple,
    modifier: Modifier = Modifier
) {
    // Состояние вращения/зума — сохраняется при recompose, не при rotation.
    var yaw by remember { mutableFloatStateOf(0.6f) }      // вращение вокруг Y
    var pitch by remember { mutableFloatStateOf(0.4f) }    // вращение вокруг X
    var scale by remember { mutableFloatStateOf(1f) }

    // Авто-масштаб считаем один раз на новый набор данных, а НЕ в draw-скоупе
    // на каждый кадр/жест: при pan/zoom данные не меняются. Без flatten() —
    // тот аллоцировал три List<Float> на каждый recompose.
    val dataKey = a1.xs.size + a1.zs.size + a2.xs.size + a2.zs.size +
        (a1.xs.lastOrNull()?.t ?: 0L).toInt() + (a2.xs.lastOrNull()?.t ?: 0L).toInt()
    val maxAbs = remember(dataKey) {
        var m = 1f
        for (tr in arrayOf(a1, a2)) {
            for (lst in arrayOf(tr.xs, tr.ys, tr.zs)) {
                for (p in lst) { val a = kotlin.math.abs(p.value); if (a > m) m = a }
            }
        }
        m
    }

    Canvas(
        modifier = modifier
            // Один pointerInput, который обрабатывает И вращение (1-палец pan),
            // И зум (pinch) — иначе detectDragGestures и detectTransformGestures
            // конкурируют за первый палец и pinch плохо ловится.
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.3f, 5f)
                    yaw = wrapAngle(yaw + pan.x / 200f)
                    pitch = (pitch + pan.y / 200f).coerceIn(-1.5f, 1.5f)
                }
            }
    ) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f

        val worldScale = (min(w, h) * 0.35f / max(maxAbs, 1f)) * scale

        // Проекционные параметры. focal в формуле сокращается — оставляем именно
        // worldScale/depth, что даёт ослабленную (мягкую) перспективу. Лучше
        // читается для облака ~600 точек на близком расстоянии.
        val camDist = max(maxAbs, 1f) * 2.5f

        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosX = cos(pitch); val sinX = sin(pitch)

        fun project(x: Float, y: Float, z: Float): Offset? {
            val x1 = x * cosY + z * sinY
            val z1 = -x * sinY + z * cosY
            val y2 = y * cosX - z1 * sinX
            val z2 = y * sinX + z1 * cosX
            val depth = z2 + camDist
            if (depth <= 0.01f) return null
            // Перспективная проекция без вырожденного focal/focal.
            val perspective = camDist / depth
            val px = cx + x1 * worldScale * perspective
            val py = cy - y2 * worldScale * perspective
            return Offset(px, py)
        }

        // Оси координат — обе полуоси (+ и −), чтобы видеть знак данных.
        val axisLen = maxAbs * 0.7f
        drawAxis(::project, axisLen, axisX = true)
        drawAxis(::project, axisLen, axisY = true)
        drawAxis(::project, axisLen, axisZ = true)

        // Точки A1 (красные) и A2 (синие). Старые полупрозрачные, последняя — жирная.
        drawCloud(a1, Color(0xFFDC3232), ::project)
        drawCloud(a2, Color(0xFF3264DC), ::project)
    }
}

/** Нормализует угол в [-π, π], чтобы не накапливать большие значения и не терять точность. */
private fun wrapAngle(a: Float): Float {
    val twoPi = (2 * PI).toFloat()
    var r = a % twoPi
    if (r > PI) r -= twoPi
    if (r < -PI) r += twoPi
    return r
}

/**
 * Тройка временных рядов одного акселерометра.
 *
 * Раньше [triples] возвращал `List<Triple<Float,Float,Float>>` — аллоцировал
 * 600 объектов Triple на каждый кадр гестур-хендлера (~60 fps × 2 облака).
 * Заменили на прямой доступ по индексу — Canvas-цикл сам ходит i in 0..n
 * и берёт xs[i].value/ys[i].value/zs[i].value без промежуточных аллокаций.
 */
data class AccelTriple(
    val xs: List<TimedPoint>,
    val ys: List<TimedPoint>,
    val zs: List<TimedPoint>
) {
    fun flatten(): List<Float> = xs.map { it.value } + ys.map { it.value } + zs.map { it.value }

    /** Количество (x,y,z)-троек, доступных одновременно. */
    val count: Int get() = minOf(xs.size, ys.size, zs.size)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxis(
    project: (Float, Float, Float) -> Offset?,
    len: Float,
    axisX: Boolean = false,
    axisY: Boolean = false,
    axisZ: Boolean = false
) {
    // Рисуем обе полуоси (от -len до +len), чтобы видеть знак данных.
    val negEnd = project(
        if (axisX) -len else 0f,
        if (axisY) -len else 0f,
        if (axisZ) -len else 0f
    ) ?: return
    val posEnd = project(
        if (axisX) len else 0f,
        if (axisY) len else 0f,
        if (axisZ) len else 0f
    ) ?: return
    val color = when {
        axisX -> Color.Red
        axisY -> Color.Green
        else -> Color(0xFF4080FF)
    }
    drawLine(color = color.copy(alpha = 0.6f), start = negEnd, end = posEnd, strokeWidth = 2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(
    triple: AccelTriple,
    color: Color,
    project: (Float, Float, Float) -> Offset?
) {
    val n = triple.count
    if (n == 0) return
    // Прямой проход по индексу — без аллокации List<Triple>.
    val xs = triple.xs; val ys = triple.ys; val zs = triple.zs
    val lastIdx = n - 1
    val invN = 1f / n
    for (idx in 0 until n) {
        val p = project(xs[idx].value, ys[idx].value, zs[idx].value) ?: continue
        val ageAlpha = (idx + 1f) * invN
        drawCircle(
            color = color.copy(alpha = 0.2f + 0.8f * ageAlpha),
            radius = 3f,
            center = p,
            style = Stroke(width = if (idx == lastIdx) 4f else 1f)
        )
    }
}
