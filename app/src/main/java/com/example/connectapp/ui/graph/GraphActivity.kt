package com.example.connectapp.ui.graph

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.databinding.ActivityGraphBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraphBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Графики датчиков"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupChart(
            chart = binding.chartTemp,
            noDataText = "Нажмите «temp» или включите авто-опрос"
        )
        setupChart(
            chart = binding.chartAccel,
            noDataText = "Нажмите «accel» или включите авто-опрос"
        )

        binding.btnGraphTemp.setOnClickListener { CommandBus.send("temp") }
        binding.btnGraphAccel.setOnClickListener { CommandBus.send("accel") }
        binding.btnGraphMonitor.setOnClickListener {
            CommandBus.send("monitor")
            // Toggle button appearance to hint at active monitoring.
            val active = binding.btnGraphMonitor.tag as? Boolean ?: false
            binding.btnGraphMonitor.tag = !active
            binding.btnGraphMonitor.text = if (!active) "⏹ stop" else "monitor"
        }
        binding.btnExportCsv.setOnClickListener { exportCsv() }

        observeData()
    }

    private fun setupChart(chart: LineChart, noDataText: String) {
        chart.apply {
            description.isEnabled = false
            setNoDataText(noDataText)
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
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    SensorDataBus.tempValues
                        .debounce(Constants.CHART_THROTTLE_MS)
                        .collect { values -> updateTempChart(values) }
                }

                launch {
                    combine(
                        SensorDataBus.accelX,
                        SensorDataBus.accelY,
                        SensorDataBus.accelZ
                    ) { x, y, z -> Triple(x, y, z) }
                        .debounce(Constants.CHART_THROTTLE_MS)
                        .collect { (x, y, z) -> updateAccelChart(x, y, z) }
                }
            }
        }
    }

    private fun updateTempChart(values: List<Float>) {
        if (values.isEmpty()) {
            binding.chartTemp.clear()
            binding.chartTemp.invalidate()
            return
        }
        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "Температура °C").apply {
            color = Color.rgb(220, 50, 50)
            setCircleColor(Color.rgb(220, 50, 50))
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        binding.chartTemp.data = LineData(dataSet)
        binding.chartTemp.invalidate()
    }

    private fun updateAccelChart(xVals: List<Float>, yVals: List<Float>, zVals: List<Float>) {
        if (xVals.isEmpty() && yVals.isEmpty() && zVals.isEmpty()) {
            binding.chartAccel.clear()
            binding.chartAccel.invalidate()
            return
        }

        fun makeSet(values: List<Float>, label: String, color: Int): LineDataSet {
            val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
            return LineDataSet(entries, label).apply {
                this.color = color
                setCircleColor(color)
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
        }

        val sets = listOf(
            makeSet(xVals, "X", Color.rgb(220, 50, 50)),
            makeSet(yVals, "Y", Color.rgb(50, 180, 50)),
            makeSet(zVals, "Z", Color.rgb(50, 100, 220))
        )
        binding.chartAccel.data = LineData(sets)
        binding.chartAccel.invalidate()
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    private fun exportCsv() {
        val csv = buildCsv()
        if (csv == null) {
            Toast.makeText(this, "Нет данных для экспорта", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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

    /**
     * Builds CSV content from [SensorDataBus].
     * Returns null if there is no data at all.
     *
     * Format:
     * Index, Temperature (C), Accel X, Accel Y, Accel Z
     */
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
                val temp = temps.getOrNull(i)?.let { "%.2f".format(it) } ?: ""
                val x = xs.getOrNull(i)?.let { "%.0f".format(it) } ?: ""
                val y = ys.getOrNull(i)?.let { "%.0f".format(it) } ?: ""
                val z = zs.getOrNull(i)?.let { "%.0f".format(it) } ?: ""
                appendLine("$i,$temp,$x,$y,$z")
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
