package com.example.connectapp.ui.test

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.ui.wifi.StatusBadge
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Тестовый экран: имитирует поток платы PIC24FJ128GB106 моковыми данными.
 * Полезен чтобы проверить парсер/графики/CSV без реального железа.
 */
class TestActivity : ComponentActivity() {

    private val viewModel: TestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                TestScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onOpenGraphs = { startActivity(Intent(this, GraphActivity::class.java)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestScreen(
    viewModel: TestViewModel,
    onBack: () -> Unit,
    onOpenGraphs: () -> Unit
) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val sensor by viewModel.sensorData.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val lastPacketAt by viewModel.lastPacketAt.collectAsStateWithLifecycle()

    val fakeState = if (running) ConnectionState.Connected else ConnectionState.Idle

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Тест (mock)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGraphs) {
                        Icon(Icons.Filled.Insights, contentDescription = stringResource(R.string.btn_graphs))
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
            StatusBadge(state = fakeState)
            HeartbeatRow(running = running, lastPacketAt = lastPacketAt)

            SensorPanel(data = sensor)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.toggle() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Остановить" else "Запустить mock-поток")
                }
                IconButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.btn_clear))
                }
            }

            Text(stringResource(R.string.label_log), style = MaterialTheme.typography.titleLarge)
            LogView(
                log = log,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun HeartbeatRow(running: Boolean, lastPacketAt: Long?) {
    if (!running || lastPacketAt == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val ageMs = (now - lastPacketAt).coerceAtLeast(0)
    val ageText = when {
        ageMs < 1500 -> stringResource(R.string.hb_just_now)
        ageMs < 60_000 -> stringResource(R.string.hb_seconds_ago, (ageMs / 1000).toInt())
        else -> stringResource(R.string.hb_minutes_ago, (ageMs / 60_000).toInt())
    }
    Text(
        "${stringResource(R.string.hb_label)} $ageText",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SensorPanel(data: SensorData) {
    if (!data.hasAnyTemp && !data.hasAnyAccel) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (data.hasAnyTemp) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    data.temperature1?.let { SensorTile(Icons.Filled.Thermostat, "T1", "%.1f°C".format(Locale.ROOT, it)) }
                    data.temperature2?.let { SensorTile(Icons.Filled.Thermostat, "T2", "%.1f°C".format(Locale.ROOT, it)) }
                }
            }
            if (data.hasAnyAccel) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (data.accel1X != null || data.accel1Y != null || data.accel1Z != null) {
                        SensorTile(
                            Icons.Filled.Tune, "A1",
                            "X:%.0f Y:%.0f Z:%.0f".format(Locale.ROOT, data.accel1X ?: 0f, data.accel1Y ?: 0f, data.accel1Z ?: 0f)
                        )
                    }
                    if (data.accel2X != null || data.accel2Y != null || data.accel2Z != null) {
                        SensorTile(
                            Icons.Filled.Tune, "A2",
                            "X:%.0f Y:%.0f Z:%.0f".format(Locale.ROOT, data.accel2X ?: 0f, data.accel2Y ?: 0f, data.accel2Z ?: 0f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorTile(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
