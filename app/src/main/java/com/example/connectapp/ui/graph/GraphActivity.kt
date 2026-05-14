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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.ui.theme.ConnectAppTheme
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
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
            Toast.makeText(this, "Нет данных для экспорта", Toast.LENGTH_SHORT).show()
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
            putExtra(Intent.EXTRA_SUBJECT, "Данные датчиков ConnectApp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Экспорт данных"))
    }

    private fun buildCsv(): String? {
        val temps = SensorDataBus.tempValues.value
        val xs = SensorDataBus.accelX.value
        val ys = SensorDataBus.accelY.value
        val zs = SensorDataBus.accelZ.value
        val maxLen = maxOf(temps.size, xs.size, ys.size, zs.size)
        if (maxLen == 0) return null
        return buildString {
            appendLine("Index,Temperature (C),Accel X,Accel Y,Accel Z")
            for (i in 0 until maxLen) {
                // Locale.ROOT — иначе RU-локаль вставит «28,50» и колонки CSV развалятся.
                val t = temps.getOrNull(i)?.let { "%.2f".format(Locale.ROOT, it) } ?: ""
                val x = xs.getOrNull(i)?.let { "%.0f".format(Locale.ROOT, it) } ?: ""
                val y = ys.getOrNull(i)?.let { "%.0f".format(Locale.ROOT, it) } ?: ""
                val z = zs.getOrNull(i)?.let { "%.0f".format(Locale.ROOT, it) } ?: ""
                appendLine("$i,$t,$x,$y,$z")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphScreen(onBack: () -> Unit, onExport: () -> Unit) {
    val temps by SensorDataBus.tempValues.collectAsStateWithLifecycle()
    val xs by SensorDataBus.accelX.collectAsStateWithLifecycle()
    val ys by SensorDataBus.accelY.collectAsStateWithLifecycle()
    val zs by SensorDataBus.accelZ.collectAsStateWithLifecycle()
    var monitoring by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Графики датчиков") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "CSV")
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Управление: запросы по команде + переключатель monitor
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { CommandBus.send("temp") },
                    modifier = Modifier.weight(1f)
                ) { Text("temp") }
                FilledTonalButton(
                    onClick = { CommandBus.send("accel") },
                    modifier = Modifier.weight(1f)
                ) { Text("accel") }
                Button(
                    onClick = {
                        CommandBus.send("monitor")
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

            Text("Температура °C", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                TemperatureChart(values = temps)
            }

            Text("Акселерометр", style = MaterialTheme.typography.titleLarge)
            ChartCard {
                AccelChart(xs = xs, ys = ys, zs = zs)
            }
        }
    }
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

@Composable
private fun TemperatureChart(values: List<Float>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                setNoDataText("Нажмите «temp» или включите авто-опрос")
                setNoDataTextColor(Color.GRAY)
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                setDrawGridBackground(false)
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    granularity = 1f
                }
                axisLeft.setDrawGridLines(true)
                axisRight.isEnabled = false
                legend.isEnabled = true
            }
        },
        update = { chart ->
            if (values.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
            val set = LineDataSet(entries, "Температура °C").apply {
                color = Color.rgb(220, 50, 50)
                setCircleColor(Color.rgb(220, 50, 50))
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            chart.data = LineData(set)
            chart.invalidate()
        }
    )
}

@Composable
private fun AccelChart(xs: List<Float>, ys: List<Float>, zs: List<Float>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                setNoDataText("Нажмите «accel» или включите авто-опрос")
                setNoDataTextColor(Color.GRAY)
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                setDrawGridBackground(false)
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    granularity = 1f
                }
                axisLeft.setDrawGridLines(true)
                axisRight.isEnabled = false
                legend.isEnabled = true
            }
        },
        update = { chart ->
            if (xs.isEmpty() && ys.isEmpty() && zs.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            fun makeSet(values: List<Float>, label: String, color: Int) = LineDataSet(
                values.mapIndexed { i, v -> Entry(i.toFloat(), v) }, label
            ).apply {
                this.color = color
                setCircleColor(color)
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
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
