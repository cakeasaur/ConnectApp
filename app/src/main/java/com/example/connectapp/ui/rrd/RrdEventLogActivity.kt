package com.example.connectapp.ui.rrd

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.utils.RrdEvent
import com.example.connectapp.utils.RrdLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий RRD платы (вывод `log dump`). Источник — [RrdLog], который
 * авто-детектит блок `=== RRD Event Log ===` в потоке и парсит его. Действия:
 * экспорт в CSV, очистка.
 */
class RrdEventLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                RrdEventLogScreen(
                    onBack = { finish() },
                    onExportCsv = { exportCsv() },
                    onExportPdf = { exportPdf() },
                )
            }
        }
    }

    private fun exportCsv() {
        val dump = RrdLog.dump.value
        val events = dump?.events.orEmpty()
        if (events.isEmpty()) {
            Toast.makeText(this, "Журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(cacheDir, "rrd_event_log_$ts.csv")
        file.bufferedWriter().use { w ->
            // Метаданные снимка — когда выгружено с устройства.
            w.write("# RRD Event Log · снято: ${capturedText(dump?.capturedAt)} · записей: ${events.size}\n")
            w.write("idx,datetime,event,marker,cur,min,max,unit,dur\n")
            for (e in events) {
                w.write(
                    "${e.idx},${csv(e.dateTime)},${csv(e.event)},${csv(e.marker)}," +
                        "${csv(e.cur)},${csv(e.min)},${csv(e.max)},${csv(e.unit)},${csv(e.dur)}\n"
                )
            }
        }
        share(file, "text/csv", "Экспорт журнала событий")
    }

    private fun exportPdf() {
        val dump = RrdLog.dump.value
        val events = dump?.events.orEmpty()
        if (events.isEmpty()) {
            Toast.makeText(this, "Журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(cacheDir, "rrd_event_log_$ts.pdf")

        val pageW = 595; val pageH = 842; val margin = 40f
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true; color = Color.BLACK }
        val small = Paint().apply { textSize = 11f; color = Color.DKGRAY }
        val mono = Paint().apply { textSize = 9f; color = Color.BLACK; typeface = Typeface.MONOSPACE }
        val rowFmt = "%-4s %-19s %-12s %-2s %-6s %-6s %-6s %-5s %-4s"
        val header = String.format(
            rowFmt, "Idx", "Date/Time", "Event", "S", "Cur", "Min", "Max", "Unit", "Dur"
        )

        val pdf = PdfDocument()
        try {
            var pageNo = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            var c = page.canvas
            c.drawText("Журнал событий RRD", margin, 50f, title)
            c.drawText(
                "Снято: ${capturedText(dump?.capturedAt)} · записей: ${events.size}",
                margin, 72f, small
            )
            c.drawText(header, margin, 100f, mono)
            var y = 116f
            for (e in events) {
                if (y > pageH - margin) {
                    pdf.finishPage(page)
                    pageNo++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                    c = page.canvas
                    c.drawText(header, margin, 50f, mono)
                    y = 66f
                }
                val row = String.format(
                    rowFmt, e.idx.toString(), trunc(e.dateTime, 19), trunc(e.event, 12),
                    e.marker, trunc(e.cur, 6), trunc(e.min, 6), trunc(e.max, 6),
                    trunc(e.unit, 5), trunc(e.dur, 4)
                )
                c.drawText(row, margin, y, mono)
                y += 13f
            }
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        share(file, "application/pdf", "Экспорт журнала событий (PDF)")
    }

    private fun share(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                title
            )
        )
    }

    /** Экранирует поле для CSV (кавычки + запятые внутри значения). */
    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"${s.replace("\"", "\"\"")}\"" else s

    private fun trunc(s: String, n: Int): String = if (s.length > n) s.substring(0, n) else s
}

private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** Человекочитаемое время снимка дампа. */
private fun capturedText(epochMs: Long?): String =
    if (epochMs == null || epochMs == 0L) "—" else tsFmt.format(Date(epochMs))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RrdEventLogScreen(onBack: () -> Unit, onExportCsv: () -> Unit, onExportPdf: () -> Unit) {
    val dump by RrdLog.dump.collectAsStateWithLifecycle()
    val events = dump?.events.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал событий (${events.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onExportCsv, enabled = events.isNotEmpty()) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Экспорт CSV")
                    }
                    IconButton(onClick = onExportPdf, enabled = events.isNotEmpty()) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Экспорт PDF")
                    }
                    IconButton(onClick = { RrdLog.clear() }, enabled = events.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Очистить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (events.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Нет дампа",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Нажми «dump» на экране Графиков — журнал событий платы появится здесь.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        "Снято: ${capturedText(dump?.capturedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Ключ по позиции, а не по idx — плата может прислать
                        // повторяющиеся idx, и key={it.idx} уронил бы LazyColumn.
                        itemsIndexed(events) { _, e -> RrdRow(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RrdRow(e: RrdEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "#${e.idx}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${e.event} · ${e.marker}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${e.dateTime}  ·  cur ${e.cur} / min ${e.min} / max ${e.max} ${e.unit} · dur ${e.dur}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
