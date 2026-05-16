package com.example.connectapp.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.models.TimedPoint

/**
 * Persistence display — фосфор-стиль осциллографа.
 *
 * Накапливает последние N фреймов сигнала (каждый = окно последних M
 * отсчётов на момент захвата) и рисует все одновременно с alpha-fade:
 * новые — яркие, старые — почти невидимые. Имитирует послесвечение CRT.
 *
 * На периодическом/повторяющемся сигнале это даёт характерный "тоннель"
 * — наложение многих циклов синусоиды создаёт яркую сплошную линию там
 * где сигнал стабильный, и размытие там где есть джиттер.
 *
 * BlendMode.Plus при отрисовке имитирует additive phosphor — пересечения
 * линий становятся ярче (как на настоящем CRT).
 */
private const val MAX_FRAMES = 25
private const val FRAME_SIZE = 128

/** Phosphor green — классический Tektronix P31. */
private val PhosphorGreen = Color(0xFF00FF41)
private val ScopeBackground = Color(0xFF0A0F0A)
private val GridLine = Color(0xFF1A3320)

/**
 * Хранит ring-buffer фреймов сигнала между recompose'ами. Привязан к
 * [remember] с ключом generation — пересоздаётся при clear() bus'а.
 *
 * frames доступен напрямую (val) — рендер итерирует по нему БЕЗ
 * toList()-копии, что критично на горячем пути (была аллокация на
 * каждый recompose × 2 чтения).
 */
private class PersistenceBuffer(val maxFrames: Int, val frameSize: Int) {
    val frames = ArrayDeque<FloatArray>(maxFrames)
    private var lastT = Long.MIN_VALUE

    /** Добавляет новый snapshot если пришли новые данные. */
    fun ingest(series: List<TimedPoint>) {
        val n = series.size
        if (n < frameSize) return
        val lastTNow = series.last().t
        if (lastTNow == lastT) return
        lastT = lastTNow
        val frame = FloatArray(frameSize) { series[n - frameSize + it].value }
        frames.addLast(frame)
        if (frames.size > maxFrames) frames.removeFirst()
    }
}

@Composable
fun PersistenceCard(
    series: List<TimedPoint>,
    generation: Int,
    modifier: Modifier = Modifier
) {
    if (series.size < FRAME_SIZE) {
        Card(
            modifier = modifier.fillMaxWidth().height(80.dp),
            colors = CardDefaults.cardColors(containerColor = ScopeBackground)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "phosphor: накопление… нужно ≥$FRAME_SIZE отсчётов",
                    color = PhosphorGreen.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        return
    }

    // Persistent state — переживает recompose, сбрасывается с generation.
    val buffer = remember(generation) { PersistenceBuffer(MAX_FRAMES, FRAME_SIZE) }
    val lastT = series.last().t
    // tick — счётчик "версии" буфера; читается ниже чтобы Canvas пере-
    // отрисовался когда ingest обновил frames. Side-effect — в LaunchedEffect
    // (НЕ в remember{}, там запрещено побочное состояние — composition
    // может быть отменена и состояние рассинхронится).
    var tick by remember(generation) { mutableIntStateOf(0) }
    LaunchedEffect(lastT) {
        buffer.ingest(series)
        tick++
    }
    // Касание tick внутри композиции — чтобы Canvas re-recomposed при апдейте.
    @Suppress("UNUSED_VARIABLE") val _tick = tick

    Card(
        modifier = modifier.fillMaxWidth().height(180.dp),
        colors = CardDefaults.cardColors(containerColor = ScopeBackground)
    ) {
        Column(Modifier.padding(8.dp).fillMaxSize()) {
            Text(
                "PERSISTENCE · ${buffer.frames.size}/$MAX_FRAMES traces · $FRAME_SIZE samples/frame",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = PhosphorGreen.copy(alpha = 0.7f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp)
                    .background(ScopeBackground)
            ) {
                PersistenceCanvas(buffer.frames)
                // Scope-метка "10 div / 8 div" в правом верхнем углу.
                Text(
                    "DIV 10×8",
                    color = PhosphorGreen.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(4.dp).align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
private fun PersistenceCanvas(frames: ArrayDeque<FloatArray>) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Scope-сетка: 10 делений по X, 8 по Y. Major lines чуть ярче.
        val divX = 10; val divY = 8
        for (i in 1 until divX) {
            val x = w * i / divX
            drawLine(GridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 0.5f)
        }
        for (i in 1 until divY) {
            val y = h * i / divY
            drawLine(GridLine, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
        }
        // Центральные оси чуть ярче
        drawLine(GridLine.copy(alpha = 0.8f), Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1f)
        drawLine(GridLine.copy(alpha = 0.8f), Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)

        if (frames.isEmpty()) return@Canvas

        // Общий Y-диапазон по всем фреймам — стабильнее визуально
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        for (f in frames) for (v in f) {
            if (v < yMin) yMin = v
            if (v > yMax) yMax = v
        }
        val pad = ((yMax - yMin) * 0.1f).coerceAtLeast(1f)
        val rangeY = (yMax - yMin + 2 * pad).coerceAtLeast(1f)
        val yMinPad = yMin - pad

        val n = frames.size
        val xStep = w / (FRAME_SIZE - 1).toFloat()

        // Рисуем от старых к новым (старые в фоне, новые поверх).
        // BlendMode.Plus → перекрытия суммируются — phosphor accumulation эффект.
        for ((fi, frame) in frames.withIndex()) {
            // age: 0=самый старый, 1=самый новый.
            val age = (fi + 1f) / n
            val alpha = (0.05f + age * 0.7f).coerceAtMost(1f)
            val color = PhosphorGreen.copy(alpha = alpha)

            var prevX = 0f
            var prevY = h - (frame[0] - yMinPad) / rangeY * h
            for (i in 1 until frame.size) {
                val x = i * xStep
                val y = h - (frame[i] - yMinPad) / rangeY * h
                drawLine(
                    color = color,
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 1.4f,
                    blendMode = BlendMode.Plus
                )
                prevX = x; prevY = y
            }
        }

    }
}
