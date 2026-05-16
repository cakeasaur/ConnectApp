package com.example.connectapp.ui.history

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.connectapp.R
import com.example.connectapp.ui.test.TestActivity
import com.example.connectapp.ui.theme.AppThemeWithSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран истории записанных сессий (session_*.txt из externalCacheDir/sessions).
 *
 * Каждая запись — карточка с датой, размером и тремя действиями:
 *   ▶ Replay  — открывает TestActivity с этим файлом как источником
 *   ⬆ Share   — шарит через FileProvider (тот же поток что в BT-экране)
 *   🗑 Delete — после подтверждения удаляет файл и обновляет список
 */
class HistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                HistoryScreen(
                    onBack = { finish() },
                    onReplay = { file ->
                        startActivity(
                            Intent(this, TestActivity::class.java)
                                .putExtra(TestActivity.EXTRA_REPLAY_FILE, file.absolutePath)
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(onBack: () -> Unit, onReplay: (File) -> Unit) {
    val ctx = LocalContext.current
    // Список перечитываем при каждом возврате на экран (LaunchedEffect(Unit)
    // в Compose отрабатывает один раз, но MutableState позволяет refresh
    // после Delete/Share-возврата).
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        files = loadSessions(ctx.externalCacheDir, ctx.cacheDir)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    SessionCard(
                        file = file,
                        onReplay = { onReplay(file) },
                        onShare = { shareSession(ctx, file) },
                        onDelete = { pendingDelete = file }
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        DeleteConfirmDialog(
            file = toDelete,
            onCancel = { pendingDelete = null },
            onConfirm = {
                toDelete.delete()
                pendingDelete = null
                files = loadSessions(ctx.externalCacheDir, ctx.cacheDir)
            }
        )
    }
}

@Composable
private fun SessionCard(
    file: File,
    onReplay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                formatMeta(file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onReplay) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.history_replay),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.history_share)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(file: File, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.history_delete_title)) },
        text = { Text(stringResource(R.string.history_delete_msg, file.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.history_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.btn_back)) }
        }
    )
}

// ============================================================
// helpers
// ============================================================

/**
 * Список session-файлов с обоих кэш-директорий, отсортирован "новые сверху".
 * Берём externalCacheDir в первую очередь — туда пишет [SessionRecorder].
 * cacheDir используется только если external недоступен (редкий случай).
 */
private fun loadSessions(externalCache: File?, cache: File): List<File> {
    val dirs = listOfNotNull(externalCache, cache).map { File(it, "sessions") }
    return dirs.flatMap { dir ->
        dir.listFiles { f -> f.name.startsWith("session_") && f.name.endsWith(".txt") }
            ?.toList() ?: emptyList()
    }.sortedByDescending { it.lastModified() }
}

private fun formatMeta(file: File): String {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        .format(Date(file.lastModified()))
    val sizeKb = file.length() / 1024
    return "$dateStr · ${sizeKb} КБ"
}

private fun shareSession(ctx: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.rec_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.rec_share)))
    }.onFailure {
        Toast.makeText(ctx, "Share failed: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}

