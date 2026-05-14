package com.example.connectapp.ui.graph

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.ui.theme.ConnectAppTheme
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GraphActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConnectAppTheme {
                GraphScreen(
                    onBack = { finish() },
                    onExport = { exportCsv() }
                )
            }
        }
    }

    private fun exportCsv() {
        val csv = buildCsv()
        if (csv == null) {
            Toast.makeText(this, getString(R.string.graph_no_export_data), Toast.LENGTH_SHORT).show()
            return
        }
        // Чистим старые экспорты, чтобы cacheDir не пух (ОС подчистит сам, но лучше явно).
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
        // Все ряды могут иметь разную длину — сводим по объединённой timeline.
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
        // Индексируем точки по timestamp для быстрого lookup.
        val maps = series.map { (_, list) -> list.associateBy { it.t } }
        return buildString {
            append("Timestamp,")
            append(series.joinToString(",") { it.first })
            append('\n')
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
private fun GraphScreen(onBack: () -> Unit, onExport: () -> Unit) {
    val temp1 by SensorDataBus.temp1.collectAsStateWithLifecycle()
    val temp2 by SensorDataBus.temp2.collectAsStateWithLifecycle()
    val a1x by SensorDataBus.accel1X.collectAsStateWithLifecycle()
    val a1y by SensorDataBus.accel1Y.collectAsStateWithLifecycle()
    val a1z by SensorDataBus.accel1Z.collectAsStateWithLifecycle()
    val a2x by SensorDataBus.accel2X.collectAsStateWithLifecycle()
    val a2y by SensorDataBus.accel2Y.collectAsStateWithLifecycle()
    val a2z by SensorDataBus.accel2Z.collectAsStateWithLifecycle()
    var monitoring by remember { mutableStateOf(false) }

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
            val cmdTemp = stringResource(R.string.cmd_temp)
            val cmdAccel = stringResource(R.string.cmd_accel)
            val cmdMonitor = stringResource(R.string.cmd_monitor)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdTemp) },
                    modifier = Modifier.weight(1f)
                ) { Text(cmdTemp) }
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdAccel) },
                    modifier = Modifier.weight(1f)
                ) { Text(cmdAccel) }
                Button(
                    onClick = {
                        CommandBus.send(cmdMonitor)
                        monitoring = !monitoring
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (monitoring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (monitoring) "stop" else cmdMonitor)
                }
            }

            Text(stringResource(R.string.label_temperature_unit), style = MaterialTheme.typography.titleLarge)
            TempStatsRow("T1", temp1)
            TempStatsRow("T2", temp2)
            ChartCard {
                TwoLineTempChart(temp1 = temp1, temp2 = temp2)
            }

            Text("${stringResource(R.string.label_accelerometer)} 1", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                AccelChart(xs = a1x, ys = a1y, zs = a1z, hintRes = R.string.graph_accel_hint)
            }

            Text("${stringResource(R.string.label_accelerometer)} 2", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                AccelChart(xs = a2x, ys = a2y, zs = a2z, hintRes = R.string.graph_accel_hint)
            }
        }
    }
}

@Composable
private fun TempStatsRow(label: String, points: List<TimedPoint>) {
    if (points.isEmpty()) return
    val values = points.map { it.value }
    val mn = values.min()
    val mx = values.max()
    val avg = values.average()
    Text(
        "$label: min %.2f / avg %.2f / max %.2f °C".format(Locale.ROOT, mn, avg, mx),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        androidx.compose.foundation.layout.Box(Modifier.padding(8.dp)) { content() }
    }
}

private class TimeAxisFormatter : ValueFormatter() {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    override fun getFormattedValue(value: Float): String = fmt.format(Date(value.toLong()))
}

private fun configureChart(chart: LineChart, noDataText: String) {
    chart.description.isEnabled = false
    chart.setNoDataText(noDataText)
    chart.setNoDataTextColor(Color.GRAY)
    chart.setTouchEnabled(true)
    chart.isDragEnabled = true
    chart.setScaleEnabled(true)
    chart.setPinchZoom(true)
    chart.setDrawGridBackground(false)
    chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
    chart.xAxis.setDrawGridLines(true)
    chart.xAxis.valueFormatter = TimeAxisFormatter()
    // labelCount=4 не даёт меткам слипнуться при компактном экране.
    chart.xAxis.setLabelCount(4, true)
    chart.axisLeft.setDrawGridLines(true)
    chart.axisRight.isEnabled = false
    chart.legend.isEnabled = true
}

private fun makeSet(points: List<TimedPoint>, label: String, color: Int): LineDataSet {
    val entries = points.map { Entry(it.t.toFloat(), it.value) }
    return LineDataSet(entries, label).apply {
        this.color = color
        setCircleColor(color)
        lineWidth = 2f
        circleRadius = 2f
        setDrawCircleHole(false)
        setDrawValues(false)
        mode = LineDataSet.Mode.CUBIC_BEZIER
    }
}

@Composable
private fun TwoLineTempChart(temp1: List<TimedPoint>, temp2: List<TimedPoint>) {
    val noDataText = stringResource(R.string.graph_temp_hint)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> LineChart(ctx).also { configureChart(it, noDataText) } },
        update = { chart ->
            if (temp1.isEmpty() && temp2.isEmpty()) {
                chart.clear(); chart.invalidate(); return@AndroidView
            }
            val sets = mutableListOf<LineDataSet>()
            if (temp1.isNotEmpty()) sets += makeSet(temp1, "T1", Color.rgb(220, 50, 50))
            if (temp2.isNotEmpty()) sets += makeSet(temp2, "T2", Color.rgb(50, 100, 220))
            chart.data = LineData(sets.toList<LineDataSet>())
            chart.invalidate()
        }
    )
}

@Composable
private fun AccelChart(
    xs: List<TimedPoint>,
    ys: List<TimedPoint>,
    zs: List<TimedPoint>,
    hintRes: Int
) {
    val noDataText = stringResource(hintRes)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> LineChart(ctx).also { configureChart(it, noDataText) } },
        update = { chart ->
            if (xs.isEmpty() && ys.isEmpty() && zs.isEmpty()) {
                chart.clear(); chart.invalidate(); return@AndroidView
            }
            chart.data = LineData(
                makeSet(xs, "X", Color.rgb(220, 50, 50)),
                makeSet(ys, "Y", Color.rgb(50, 180, 50)),
                makeSet(zs, "Z", Color.rgb(50, 100, 220))
            )
            chart.invalidate()
        }
    )
}
