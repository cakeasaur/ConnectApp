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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
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

            Text(stringResource(R.string.label_temperature_unit), style = MaterialTheme.typography.titleLarge)
            TempStatsRow("T1", temp1)
            TempStatsRow("T2", temp2)
            ChartCard {
                VicoLineChart(series = listOf(temp1, temp2).filter { it.isNotEmpty() })
            }

            Text("${stringResource(R.string.label_accelerometer)} 1", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                VicoLineChart(series = listOf(a1x, a1y, a1z).filter { it.isNotEmpty() })
            }

            Text("${stringResource(R.string.label_accelerometer)} 2", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                VicoLineChart(series = listOf(a2x, a2y, a2z).filter { it.isNotEmpty() })
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
 * Линейный график на Vico 1.14. Принимает 1..N серий TimedPoint, X-ось = секунды
 * с момента первой точки (Float не вмещает абсолютные 13-значные ms-timestamps).
 */
@Composable
private fun VicoLineChart(series: List<List<TimedPoint>>) {
    val producer = remember { ChartEntryModelProducer() }

    LaunchedEffect(series) {
        if (series.isEmpty() || series.all { it.isEmpty() }) {
            producer.setEntries(emptyList<List<com.patrykandpatrick.vico.core.entry.ChartEntry>>())
            return@LaunchedEffect
        }
        val baseMs = series.flatten().minOf { it.t }
        // Каждая серия → отдельный List<ChartEntry>. Vico рисует их разными линиями.
        val seriesEntries = series
            .filter { it.isNotEmpty() }
            .map { points ->
                points.map { entryOf((it.t - baseMs).toFloat() / 1000f, it.value) }
            }
        producer.setEntries(seriesEntries)
    }

    Chart(
        chart = lineChart(),
        chartModelProducer = producer,
        startAxis = rememberStartAxis(),
        bottomAxis = rememberBottomAxis(),
        modifier = Modifier.fillMaxSize()
    )
}
