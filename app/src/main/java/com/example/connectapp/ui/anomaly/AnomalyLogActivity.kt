package com.example.connectapp.ui.anomaly

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.utils.AlertEngine
import com.example.connectapp.utils.AlertEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал срабатываний пороговых алертов.
 *
 * Источник данных — [AlertEngine.events] (in-memory, 500 последних).
 * Перезапуск приложения = очистка. Это намеренно: журнал — для текущей
 * сессии мониторинга, не для долговременного хранения.
 *
 * Действия: экспорт в CSV, очистка журнала.
 */
class AnomalyLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                AnomalyLogScreen(
                    onBack = { finish() },
                    onExportCsv = { exportCsv() }
                )
            }
        }
    }

    private fun exportCsv() {
        val events = AlertEngine.events.value
        if (events.isEmpty()) {
            Toast.makeText(this, "Журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(cacheDir, "anomaly_log_$ts.csv")
        // CSV header: timestamp_ms, datetime, channel, value, threshold, exceeded_by
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        file.bufferedWriter().use { w ->
            w.write("timestamp_ms,datetime,channel,value,threshold,exceeded_by\n")
            for (e in events) {
                w.write(
                    "${e.timestamp},${df.format(Date(e.timestamp))}," +
                        "${e.channelKey},${"%.3f".format(Locale.ROOT, e.value)}," +
                        "${"%.3f".format(Locale.ROOT, e.threshold)}," +
                        "${"%.3f".format(Locale.ROOT, e.value - e.threshold)}\n"
                )
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Экспорт журнала аномалий"
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnomalyLogScreen(
    onBack: () -> Unit,
    onExportCsv: () -> Unit,
) {
    val events by AlertEngine.events.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал аномалий (${events.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onExportCsv, enabled = events.isNotEmpty()) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Экспорт CSV")
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = events.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Очистить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (events.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Журнал пуст",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Срабатывания алертов будут появляться здесь.\nНастрой пороги через ⚠ на экране Графиков.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // key: timestamp+channelKey уникальны (events добавляются только
                    // через recordEvent с миллисекундным timestamp). Без key
                    // LazyColumn пересоздавал все item-композиты при добавлении
                    // в начало списка → O(N) recompose на каждый alert.
                    items(events, key = { "${it.timestamp}-${it.channelKey}" }) { event ->
                        AnomalyRow(event)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Будут удалены все ${events.size} записей. Действие необратимо.") },
            confirmButton = {
                TextButton(onClick = {
                    AlertEngine.clearEvents()
                    showClearDialog = false
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
            }
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

@Composable
private fun AnomalyRow(event: AlertEvent) {
    val exceededBy = event.value - event.threshold
    val time = timeFormat.format(Date(event.timestamp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                time,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "⚠ ${event.channelLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "%.2f > %.2f (порог) · превышение %+.2f"
                        .format(Locale.ROOT, event.value, event.threshold, exceededBy),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
