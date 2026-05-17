package com.example.connectapp.ui.graph

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.connectapp.R
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GraphActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                GraphScreen(
                    onBack = { finish() },
                    onExport = { exportCsv() },
                    onExportPdf = { exportPdf() }
                )
            }
        }
    }

    private fun exportPdf() {
        // Снимок данных из SensorDataBus — синхронно на UI потоке (StateFlow.value
        // дёшево). Сам рендеринг PDF (FFT-256, рисование Canvas, write на диск)
        // — на Dispatchers.IO, иначе ~50-100мс ANR на крупных сериях.
        val temp1 = SensorDataBus.temp1.value
        val temp2 = SensorDataBus.temp2.value
        val a1x = SensorDataBus.accel1X.value
        val a1y = SensorDataBus.accel1Y.value
        val a1z = SensorDataBus.accel1Z.value
        val a2x = SensorDataBus.accel2X.value
        val a2y = SensorDataBus.accel2Y.value
        val a2z = SensorDataBus.accel2Z.value
        if (temp1.isEmpty() && temp2.isEmpty() && a1x.isEmpty() && a2x.isEmpty()) {
            Toast.makeText(this, getString(R.string.graph_no_export_data), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    cacheDir.listFiles { f -> f.name.startsWith("sensor_report_") && f.name.endsWith(".pdf") }
                        ?.forEach { it.delete() }
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val out = File(cacheDir, "sensor_report_$ts.pdf")
                com.example.connectapp.utils.PdfReporter.build(
                    out, temp1, temp2, a1x, a1y, a1z, a2x, a2y, a2z
                )
            }
            if (file == null) {
                Toast.makeText(this@GraphActivity, "PDF export failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@GraphActivity, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.graph_export_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.graph_export_intent_title)))
        }
    }

    private fun exportCsv() {
        val csv = buildCsv()
        if (csv == null) {
            Toast.makeText(this, getString(R.string.graph_no_export_data), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("sensor_data_") && f.name.endsWith(".csv") }
                ?.forEach { it.delete() }
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(cacheDir, "sensor_data_$timestamp.csv")
        file.writeText(csv)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.graph_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.graph_export_intent_title)))
    }

    private fun buildCsv(): String? {
        val series = listOf(
            "T1" to SensorDataBus.temp1.value,
            "T2" to SensorDataBus.temp2.value,
            "A1X" to SensorDataBus.accel1X.value,
            "A1Y" to SensorDataBus.accel1Y.value,
            "A1Z" to SensorDataBus.accel1Z.value,
            "A2X" to SensorDataBus.accel2X.value,
            "A2Y" to SensorDataBus.accel2Y.value,
            "A2Z" to SensorDataBus.accel2Z.value
        )
        val allTs = series.flatMap { it.second.map { p -> p.t } }.toSortedSet()
        if (allTs.isEmpty()) return null
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
        val maps = series.map { (_, list) -> list.associateBy { it.t } }
        return buildString {
            append("Timestamp,"); append(series.joinToString(",") { it.first }); append('\n')
            for (t in allTs) {
                append(iso.format(Date(t))); append(',')
                for ((i, _) in series.withIndex()) {
                    val v = maps[i][t]?.value
                    if (v != null) append(String.format(Locale.ROOT, "%.3f", v))
                    if (i != series.lastIndex) append(',')
                }
                append('\n')
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphScreen(onBack: () -> Unit, onExport: () -> Unit, onExportPdf: () -> Unit) {
    // Live-данные с шины. Дальше прогоняем через snapshotWhen(paused) и
    // applyWindow(window) — фильтры применяются и к графикам, и к MathSection.
    val temp1Live by SensorDataBus.temp1.collectAsStateWithLifecycle()
    val temp2Live by SensorDataBus.temp2.collectAsStateWithLifecycle()
    val a1xLive by SensorDataBus.accel1X.collectAsStateWithLifecycle()
    val a1yLive by SensorDataBus.accel1Y.collectAsStateWithLifecycle()
    val a1zLive by SensorDataBus.accel1Z.collectAsStateWithLifecycle()
    val a2xLive by SensorDataBus.accel2X.collectAsStateWithLifecycle()
    val a2yLive by SensorDataBus.accel2Y.collectAsStateWithLifecycle()
    val a2zLive by SensorDataBus.accel2Z.collectAsStateWithLifecycle()

    var monitoring by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var window by remember { mutableStateOf(TimeWindow.ALL) }
    // Overlays для NeonChart — научный режим: envelope / ±1σ / threshold.
    var showEnvelope by remember { mutableStateOf(false) }
    var showSigma by remember { mutableStateOf(false) }
    var showThreshold by remember { mutableStateOf(false) }
    // Shared crosshair-state — тап на любом из 3 line charts двигает линию
    // во ВСЕХ. Объект (не StateFlow) держит Long? state без боксинга.
    val crosshair = remember { CrosshairBus() }

    // 1) Freeze: при paused = true возвращаем снимок, снятый в момент перехода.
    val temp1Snap = snapshotWhen(paused, temp1Live)
    val temp2Snap = snapshotWhen(paused, temp2Live)
    val a1xSnap = snapshotWhen(paused, a1xLive)
    val a1ySnap = snapshotWhen(paused, a1yLive)
    val a1zSnap = snapshotWhen(paused, a1zLive)
    val a2xSnap = snapshotWhen(paused, a2xLive)
    val a2ySnap = snapshotWhen(paused, a2yLive)
    val a2zSnap = snapshotWhen(paused, a2zLive)

    // 2) Window: показываем только хвост длиной window.ms (0 = всё).
    //    remember с ключом (snap, windowMs) — не пересчитываем если данные те же.
    val windowMs = window.ms
    val temp1 = remember(temp1Snap, windowMs) { applyWindow(temp1Snap, windowMs) }
    val temp2 = remember(temp2Snap, windowMs) { applyWindow(temp2Snap, windowMs) }
    val a1x = remember(a1xSnap, windowMs) { applyWindow(a1xSnap, windowMs) }
    val a1y = remember(a1ySnap, windowMs) { applyWindow(a1ySnap, windowMs) }
    val a1z = remember(a1zSnap, windowMs) { applyWindow(a1zSnap, windowMs) }
    val a2x = remember(a2xSnap, windowMs) { applyWindow(a2xSnap, windowMs) }
    val a2y = remember(a2ySnap, windowMs) { applyWindow(a2ySnap, windowMs) }
    val a2z = remember(a2zSnap, windowMs) { applyWindow(a2zSnap, windowMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graph_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    // Pause — заморозить графики и stats на текущем кадре, чтобы
                    // успеть прочитать значения, пока поток идёт. Снежинка → нажата.
                    IconButton(onClick = { paused = !paused }) {
                        Icon(
                            Icons.Filled.AcUnit,
                            contentDescription = stringResource(
                                if (paused) R.string.graph_resume else R.string.graph_pause
                            ),
                            tint = if (paused) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onExportPdf) {
                        Icon(
                            Icons.Filled.PictureAsPdf,
                            contentDescription = stringResource(R.string.graph_export_pdf)
                        )
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.graph_export_csv))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val cmdStart = stringResource(R.string.cmd_one)
            val cmdCalib = stringResource(R.string.cmd_two)
            val cmdQuick = stringResource(R.string.cmd_three)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdCalib) },
                    modifier = Modifier.weight(1f)
                ) { Text("calib") }
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdQuick) },
                    modifier = Modifier.weight(1f)
                ) { Text("test") }
                Button(
                    onClick = {
                        CommandBus.send(cmdStart)
                        monitoring = !monitoring
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (monitoring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (monitoring) "stop" else "monitor")
                }
            }

            // Window selector — фильтр по времени для всех графиков и stats.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.graph_window_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TimeWindow.values().forEach { tw ->
                    // Явный selectedContainerColor — дефолтный FilterChip в
                    // тёмной теме слабо отличает selected от unselected,
                    // оба выглядят прозрачными чипами с обводкой.
                    FilterChip(
                        selected = window == tw,
                        onClick = { window = tw },
                        label = { Text(tw.label) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Overlays-row: envelope / ±1σ / threshold-alert. Применяются
            // ко ВСЕМ NeonChart'ам единообразно — научный режим.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "overlays:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val chipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
                FilterChip(
                    selected = showEnvelope,
                    onClick = { showEnvelope = !showEnvelope },
                    label = { Text("envelope") },
                    colors = chipColors
                )
                FilterChip(
                    selected = showSigma,
                    onClick = { showSigma = !showSigma },
                    label = { Text("±σ") },
                    colors = chipColors
                )
                FilterChip(
                    selected = showThreshold,
                    onClick = { showThreshold = !showThreshold },
                    label = { Text("⚠ alert") },
                    colors = chipColors
                )
            }

            // Neon-палитра — насыщенные неоновые цвета поверх тёмного фона
            // карточки. Не путать с цветами серий в Vico (FF3232 / 3264DC):
            // здесь они подобраны под dark BG для максимальной читаемости.
            val tempColors = listOf(Color(0xFFFF6B6B), Color(0xFF4FC3F7))
            val accelColors = listOf(Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF40C4FF))

            // Конфиги per chart. Thresholds — тематические:
            //   T > 28°C → перегрев
            //   az отклонение от 1g (1000) > 100 LSB → сильная вибрация
            val tempConfig = NeonChartConfig(
                showEnvelope = showEnvelope,
                showSigma = showSigma,
                thresholds = if (showThreshold) listOf(
                    NeonThreshold(28f, "overheat", Color(0xFFFFAA00))
                ) else emptyList()
            )
            val accelConfig = NeonChartConfig(
                showEnvelope = showEnvelope,
                showSigma = showSigma,
                thresholds = if (showThreshold) listOf(
                    NeonThreshold(1100f, "vibration", Color(0xFFFF8800), NeonAxis.RIGHT)
                ) else emptyList()
            )

            Text(stringResource(R.string.label_temperature_unit), style = MaterialTheme.typography.titleLarge)
            TempStatsRow("T1", temp1)
            TempStatsRow("T2", temp2)
            ChartCard(height = 220) {
                NeonChart(
                    seriesList = listOf(
                        NeonSeries(temp1, tempColors[0], "T1"),
                        NeonSeries(temp2, tempColors[1], "T2"),
                    ),
                    config = tempConfig,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("${stringResource(R.string.label_accelerometer)} 1", style = MaterialTheme.typography.titleLarge)
            ChartCard(height = 220) {
                // Multi-axis: az → правая ось (диапазон ~900-1100 от гравитации),
                // ax/ay → левая (±50). Без этого ax/ay сплющены в линию.
                NeonChart(
                    seriesList = listOf(
                        NeonSeries(a1x, accelColors[0], "ax", NeonAxis.LEFT),
                        NeonSeries(a1y, accelColors[1], "ay", NeonAxis.LEFT),
                        NeonSeries(a1z, accelColors[2], "az", NeonAxis.RIGHT),
                    ),
                    config = accelConfig,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("${stringResource(R.string.label_accelerometer)} 2", style = MaterialTheme.typography.titleLarge)
            ChartCard(height = 220) {
                NeonChart(
                    seriesList = listOf(
                        NeonSeries(a2x, accelColors[0], "ax", NeonAxis.LEFT),
                        NeonSeries(a2y, accelColors[1], "ay", NeonAxis.LEFT),
                        NeonSeries(a2z, accelColors[2], "az", NeonAxis.RIGHT),
                    ),
                    config = accelConfig,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("3D облако акселерометров", style = MaterialTheme.typography.titleLarge)
            Text(
                "A1 красным, A2 синим. Перетаскивай — вращай, pinch — зум.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChartCard(height = 320) {
                Accel3DChart(
                    a1 = AccelTriple(a1x, a1y, a1z),
                    a2 = AccelTriple(a2x, a2y, a2z),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Раздел "Математический анализ" получает те же отфильтрованные
            // данные что и графики выше — pause/window применяются единообразно.
            // generation = ключ для stateful-математики (Kalman): инкрементится
            // в SensorDataBus.clear(), на новом значении сбрасываем накопленный
            // state, иначе Kalman продолжит "помнить" удалённые отсчёты.
            val generation by SensorDataBus.generation.collectAsStateWithLifecycle()
            MathSection(
                t1 = temp1, t2 = temp2,
                a1x = a1x, a1y = a1y, a1z = a1z,
                a2x = a2x, a2y = a2y,
                generation = generation
            )
        }
    }
}

@Composable
private fun TempStatsRow(label: String, points: List<TimedPoint>) {
    if (points.isEmpty()) return
    val values = points.map { it.value }
    val mn = values.min(); val mx = values.max(); val avg = values.average()
    Text(
        "$label: min %.2f / avg %.2f / max %.2f °C".format(Locale.ROOT, mn, avg, mx),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChartCard(height: Int = 220, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
    ) {
        Box(Modifier.padding(8.dp)) { content() }
    }
}

/**
 * Линейный график на Vico 1.14. Принимает 1..N серий TimedPoint.
 *
 * X-ось = СЕКУНДЫ относительно baseMs, КВАНТИЗОВАННЫЕ до 0.01с (10мс).
 * Почему квантизация: Vico 1.14 кидает IllegalArgumentException("x values are
 * too precise. Maximum precision is two decimal places") если у X >2 знаков
 * после запятой. (t - baseMs) / 1000f даёт 3-знаковые значения — крашит весь
 * экран графиков.
 *
 * Также де-дублируем точки с одинаковым квантизованным X: Vico требует
 * строго возрастающую X-последовательность, иначе IllegalArgumentException.
 *
 * Модель — синхронная entryModelOf(...). Async ChartEntryModelProducer
 * страдал тем же x-precision крашем, но молча (исключение в корутине), и
 * визуально график оставался пустым.
 */
@Composable
private fun VicoLineChart(series: List<List<TimedPoint>>) {
    val nonEmpty = series.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                "нет данных — запусти monitor",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val baseMs = nonEmpty.minOf { it.first().t }

    // Квантизация: (t - base) ms → centiseconds (Long) → Float секунды с 2 знаками.
    // distinctBy(x) — Vico требует уникальные строго возрастающие X.
    // Список уже отсортирован по времени (добавляется только в конец).
    val model = remember(nonEmpty, baseMs) {
        val seriesArr: Array<List<FloatEntry>> = nonEmpty.map { points ->
            val seen = HashSet<Float>(points.size)
            points.mapNotNull { p ->
                val cs = (p.t - baseMs) / 10L  // 10ms-кванты
                val x = cs.toFloat() / 100f    // секунды × 100 → 2 знака
                if (seen.add(x)) entryOf(x, p.value) else null
            }.takeIf { it.isNotEmpty() } ?: listOf(entryOf(0f, points.first().value))
        }.toTypedArray()
        entryModelOf(*seriesArr)
    }

    // Цвета серий: T (1-2 линии) — red/blue, Accel (3) — R/G/B.
    val seriesColors = when (nonEmpty.size) {
        1 -> listOf(0xFFDC3232)
        2 -> listOf(0xFFDC3232, 0xFF3264DC)
        else -> listOf(0xFFDC3232, 0xFF32B432, 0xFF3264DC)
    }
    val lineSpecs = seriesColors.map { argb ->
        // lineBackgroundShader = null → отключаем дефолтную полупрозрачную
        // заливку под линией. Иначе T1 (красная заливка) закрывает T2 на
        // том же чарте, и видна только тонкая полоска T2 на дне.
        lineSpec(
            lineColor = androidx.compose.ui.graphics.Color(argb),
            lineBackgroundShader = null
        )
    }

    // Y-авто-зум: Vico по умолчанию ставит Ymin = min(0, dataMin), и температура
    // 22..27°C превращается в сплющенную полоску у верха. Берём min/max данных
    // + 10% padding (или ≥ 0.5 ед., чтобы плоская линия не схлопывалась).
    val allY = nonEmpty.flatMap { it.asSequence().map(TimedPoint::value).toList() }
    val yMin = allY.min()
    val yMax = allY.max()
    val pad = ((yMax - yMin) * 0.1f).coerceAtLeast(0.5f)
    val yOverrider = remember(yMin, yMax) {
        AxisValuesOverrider.fixed(minY = yMin - pad, maxY = yMax + pad)
    }

    // X-метки: адаптивно по диапазону данных.
    //   < 10с  → "0.0s, 0.5s, 1.0s"  (одна десятая — иначе всё "0s")
    //   ≥ 10с  → "0s, 12s, 24s"       (целые секунды)
    // Раньше всегда был toInt() → на коротком окне (<1с) ВСЕ метки = "0s".
    val maxX = nonEmpty.maxOf { (it.last().t - baseMs) / 10L } / 100f
    val timeFmt = remember(maxX < 10f) {
        val showDecimal = maxX < 10f
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
            if (showDecimal) "%.1fs".format(java.util.Locale.ROOT, value)
            else "${value.toInt()}s"
        }
    }

    Chart(
        chart = lineChart(lines = lineSpecs, axisValuesOverrider = yOverrider),
        model = model,
        startAxis = rememberStartAxis(),
        bottomAxis = rememberBottomAxis(valueFormatter = timeFmt),
        // Marker отключён — ChartWithCrosshair overlay перехватывает тапы,
        // и Vico marker всё равно не сработал бы. Crosshair даёт ТУ ЖЕ
        // функцию плюс синхронизацию по 3 чартам — строгое улучшение.
        modifier = Modifier.fillMaxSize()
    )
}
