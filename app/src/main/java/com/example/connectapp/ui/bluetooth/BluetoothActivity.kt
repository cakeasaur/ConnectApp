package com.example.connectapp.ui.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.ConnectionHistoryEntry
import com.example.connectapp.data.settings.LineEnding
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.ui.theme.ConnectAppTheme
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.ui.wifi.StatusBadge
import com.example.connectapp.utils.PermissionHelper
import com.example.connectapp.utils.stripAnsi
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class BluetoothActivity : ComponentActivity() {

    private val viewModel: BluetoothViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!viewModel.isAvailable()) {
            Toast.makeText(this, getString(R.string.toast_bluetooth_unavailable), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            ConnectAppTheme(settings = settings) {
                BluetoothScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onOpenGraphs = {
                        // Помечаем источник, чтобы CommandBus.send из графиков
                        // адресно ушёл только в BluetoothViewModel.
                        startActivity(
                            Intent(this, GraphActivity::class.java)
                                .putExtra(GraphActivity.EXTRA_TRANSPORT, "bt")
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BluetoothScreen(
    viewModel: BluetoothViewModel,
    onBack: () -> Unit,
    onOpenGraphs: () -> Unit
) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val sensor by viewModel.sensorData.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastPacketAt by viewModel.lastPacketAt.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingFile by viewModel.recordingFile.collectAsStateWithLifecycle()
    val history by viewModel.connectionHistory.collectAsStateWithLifecycle()

    var payload by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var hexMode by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var quickCmdsOpen by remember { mutableStateOf(false) }
    var profilesOpen by remember { mutableStateOf(false) }
    var calibOpen by remember { mutableStateOf(false) }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
            viewModel.maybeAutoReconnect()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.toast_bluetooth_off), Toast.LENGTH_LONG).show()
            (ctx as? ComponentActivity)?.finish()
        }
    }

    fun ensureBluetoothEnabled() {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
            viewModel.maybeAutoReconnect()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_bluetooth_perm_required), Toast.LENGTH_LONG).show()
            (ctx as? ComponentActivity)?.finish()
            return
        }
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Проверяем по факту, КАКИЕ права остались отозванными — не doverying
        // booleanам в result (там могут быть и optional как POST_NOTIFICATIONS).
        val btMissing = PermissionHelper.missing(ctx, PermissionHelper.bluetoothPermissions())
        if (btMissing.isEmpty()) {
            // Notifications могут быть отозваны — fg-service всё равно работает,
            // просто без иконки в шторке. Не блокируем экран.
            ensureBluetoothEnabled()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.toast_bluetooth_perms_required), Toast.LENGTH_LONG).show()
            (ctx as? ComponentActivity)?.finish()
        }
    }

    LaunchedEffect(Unit) {
        // BT permissions + POST_NOTIFICATIONS (для foreground service на Android 13+).
        // Спрашиваем одним диалогом — пользователь не путается.
        val needed = PermissionHelper.missing(
            ctx,
            PermissionHelper.bluetoothPermissions() + PermissionHelper.notificationPermissions()
        )
        if (needed.isEmpty()) ensureBluetoothEnabled() else permissionLauncher.launch(needed)
    }

    LaunchedEffect(state) {
        if (state is ConnectionState.Error) {
            Toast.makeText(ctx, (state as ConnectionState.Error).message, Toast.LENGTH_LONG).show()
        }
    }

    val connected = state is ConnectionState.Connected

    if (settingsOpen) {
        SettingsDialog(
            settings = settings,
            onDismiss = { settingsOpen = false },
            onAutoReconnect = viewModel::setAutoReconnect,
            onAutoMonitor = viewModel::setAutoMonitor,
            onAutoScroll = viewModel::setAutoScrollLog,
            onTheme = viewModel::setDarkTheme,
            onLineEnding = viewModel::setLineEnding,
            onHexSend = viewModel::setHexSendMode,
            onSampleRate = viewModel::setSampleRateHz,
            onAccelSensitivity = viewModel::setAccelSensitivity,
            onEditQuickCommands = { quickCmdsOpen = true },
            onOpenProfiles = { profilesOpen = true },
        )
    }

    if (quickCmdsOpen) {
        QuickCommandsDialog(
            initial = settings.quickCommands,
            onSave = { viewModel.setQuickCommands(it) },
            onDismiss = { quickCmdsOpen = false }
        )
    }

    if (profilesOpen) {
        ProfilesDialog(
            profiles = settings.boardProfiles,
            activeProfile = settings.activeProfile,
            onSaveCurrent = { viewModel.saveCurrentAsProfile(it) },
            onApply = { viewModel.applyProfile(it) },
            onDelete = { viewModel.deleteProfile(it) },
            onDismiss = { profilesOpen = false },
        )
    }

    if (calibOpen) {
        val calibLog by viewModel.calibrationLog.collectAsStateWithLifecycle()
        val calibRunning by viewModel.calibrationRunning.collectAsStateWithLifecycle()
        CalibrationDialog(
            log = calibLog,
            running = calibRunning,
            onStart = { viewModel.runCalibration() },
            onDismiss = { calibOpen = false; viewModel.resetCalibrationLog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.btn_bluetooth),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    // Часто используемое — видимыми кнопками.
                    IconButton(onClick = { viewModel.sendEscape() }, enabled = connected) {
                        Icon(Icons.Filled.Stop, contentDescription = "Стоп потока (ESC)")
                    }
                    IconButton(onClick = onOpenGraphs) {
                        Icon(Icons.Filled.Insights, contentDescription = stringResource(R.string.btn_graphs))
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    // Остальное — в overflow-меню, чтобы не распирало шапку.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isRecording) stringResource(R.string.rec_stop)
                                        else stringResource(R.string.rec_start)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                                        contentDescription = null,
                                        tint = if (isRecording) MaterialTheme.colorScheme.onSurface
                                               else MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    if (isRecording) {
                                        val f = viewModel.stopRecording() ?: recordingFile
                                        if (f != null) {
                                            Toast.makeText(ctx, ctx.getString(R.string.rec_stopped, f.name), Toast.LENGTH_LONG).show()
                                            shareRecording(ctx, f)
                                        }
                                    } else {
                                        val f = viewModel.startRecording()
                                        if (f != null) Toast.makeText(ctx, ctx.getString(R.string.rec_started, f.name), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_command_log)) },
                                leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    ctx.startActivity(Intent(ctx, com.example.connectapp.ui.console.CommandLogActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_calibrate)) },
                                leadingIcon = { Icon(Icons.Filled.GpsFixed, contentDescription = null) },
                                onClick = { menuOpen = false; calibOpen = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        // НЕ оборачиваем в Column.verticalScroll — внутри есть LogView со своим
        // прокручиваемым Box, что даст вложенный scroll одного направления и
        // ломает жесты + бросает Compose-warning'и. Вместо этого делаем все
        // элементы выше лога фиксированной высоты, а лог занимает остаток.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusBadge(state = state, onRetry = { viewModel.retry() })
            HeartbeatRow(state = state, lastPacketAt = lastPacketAt)

            SensorPanel(data = sensor)

            // Поиск устройств и история нужны только пока НЕ подключены.
            // При активном соединении скрываем их — освобождается ~250dp,
            // которые забирает лог (он на weight(1f)).
            if (!connected) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.label_devices),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (!PermissionHelper.hasAll(ctx, PermissionHelper.bluetoothPermissions())) {
                                        permissionLauncher.launch(PermissionHelper.bluetoothPermissions())
                                    } else if (viewModel.isEnabled()) {
                                        if (scanning) viewModel.stopDiscovery() else viewModel.startDiscovery()
                                    } else {
                                        ensureBluetoothEnabled()
                                    }
                                }
                            ) {
                                if (scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.btn_stop))
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.btn_scan))
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.hint_pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        DeviceList(
                            devices = devices,
                            onClick = { viewModel.connect(it.address, it.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 220.dp)
                        )
                    }
                }

                // История подключений — компактный ряд chip'ов с последними 5 устройствами.
                if (history.isNotEmpty()) {
                    HistoryRow(
                        items = history,
                        onSelect = { viewModel.connect(it.address, it.name) },
                        onClear = { viewModel.clearHistory() }
                    )
                }
            }

            // Чипы кастомных команд — readonly view, редактируется в Настройках.
            // sendQuick() читает hex/text из самой команды (не из глобальной настройки).
            CommandChips(
                commands = settings.quickCommands,
                enabled = connected,
                onCommand = { viewModel.sendQuick(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = {
                        Text(
                            if (settings.hexSendMode) stringResource(R.string.hex_send_hint)
                            else stringResource(R.string.hint_payload)
                        )
                    },
                    singleLine = true,
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (payload.isNotEmpty()) {
                            viewModel.send(payload)
                            payload = ""
                        }
                    },
                    enabled = connected && payload.isNotEmpty(),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.btn_send))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.label_log), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { hexMode = !hexMode },
                        label = { Text(if (hexMode) stringResource(R.string.log_view_hex) else stringResource(R.string.log_view_text)) }
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        enabled = connected || state is ConnectionState.Reconnecting
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_disconnect))
                    }
                    IconButton(onClick = { viewModel.clearLog() }) {
                        Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.btn_clear))
                    }
                }
            }

            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text(stringResource(R.string.log_filter)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val displayLog = remember(log, filter, hexMode) {
                renderLog(log, filter, hexMode)
            }
            // weight(1f) → лог занимает остаток высоты Scaffold-padding'а.
            LogView(
                log = displayLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                autoScroll = settings.autoScrollLog
            )
        }
    }
}

private fun renderLog(raw: String, filter: String, hexMode: Boolean): String {
    val filtered = if (filter.isBlank()) raw
        else raw.lineSequence().filter { it.contains(filter, ignoreCase = true) }.joinToString("\n")
    // Текстовый режим: убираем ANSI escape-коды + схлопываем эхо AT-консоли
    // (она перепечатывает команду посимвольно: "m> mo> mon> monitor").
    // В hex-режиме оставляем сырьё, чтобы видеть все байты включая 1b.
    if (!hexMode) return collapseEcho(stripAnsi(filtered))
    // Hex view: каждый байт UTF-8 в шестнадцатеричном виде, по 16 байт в строке.
    val bytes = filtered.toByteArray()
    return buildString {
        for (i in bytes.indices step 16) {
            for (j in 0 until 16) {
                if (i + j >= bytes.size) break
                append(String.format(Locale.ROOT, "%02X ", bytes[i + j]))
            }
            append('\n')
        }
    }
}

private fun shareRecording(ctx: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.rec_share_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.rec_share)))
}

/**
 * Схлопывает посимвольное эхо AT-консоли. Плата перепечатывает команду после
 * каждого нажатия: "m> mo> mon> monitor". Внутри строки бьём по "> " и
 * выбрасываем фрагмент, если он — префикс следующего (инкрементальный ввод),
 * и пустые фрагменты-промпты. Обычный текст (без "> ") не трогаем.
 */
private fun collapseEcho(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
        if ("> " !in line) return@joinToString line
        val parts = line.split("> ")
        val kept = ArrayList<String>(parts.size)
        for (i in parts.indices) {
            val cur = parts[i]
            if (cur.isEmpty()) continue
            val next = parts.getOrNull(i + 1)
            if (next != null && next.startsWith(cur)) continue
            kept.add(cur)
        }
        if (kept.isEmpty()) line.trim() else kept.joinToString("> ")
    }

@Composable
private fun HeartbeatRow(state: ConnectionState, lastPacketAt: Long?) {
    if (state !is ConnectionState.Connected || lastPacketAt == null) return
    // Перетриггер каждую секунду чтобы отображение возраста обновлялось.
    // mutableLongStateOf вместо mutableStateOf<Long> — без auto-boxing
    // каждый тик (1Hz × bunch of recomposes).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Перетриггерим только при смене Connected/Disconnected — не при каждом
    // полученном пакете, иначе корутина пересоздаётся ~10 раз в секунду.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ageMs = (now - lastPacketAt).coerceAtLeast(0)
    val ageText = when {
        ageMs < 1500 -> stringResource(R.string.hb_just_now)
        ageMs < 60_000 -> stringResource(R.string.hb_seconds_ago, (ageMs / 1000).toInt())
        else -> stringResource(R.string.hb_minutes_ago, (ageMs / 60_000).toInt())
    }
    // Красным — только при реальном простое (раньше 5с давало красный даже в
    // командном режиме, где поток намеренно выключен).
    val warn = ageMs > 30_000
    Text(
        "${stringResource(R.string.hb_label)} $ageText",
        style = MaterialTheme.typography.labelMedium,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onAutoReconnect: (Boolean) -> Unit,
    onAutoMonitor: (Boolean) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onTheme: (Boolean?) -> Unit,
    onLineEnding: (LineEnding) -> Unit,
    onHexSend: (Boolean) -> Unit,
    onSampleRate: (Float) -> Unit,
    onAccelSensitivity: (Float) -> Unit,
    onEditQuickCommands: () -> Unit,
    onOpenProfiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                SwitchRow(
                    label = stringResource(R.string.settings_auto_reconnect),
                    checked = settings.autoReconnect,
                    onChange = onAutoReconnect
                )
                SwitchRow(
                    label = stringResource(R.string.settings_auto_monitor),
                    checked = settings.autoMonitor,
                    onChange = onAutoMonitor
                )
                SwitchRow(
                    label = stringResource(R.string.settings_auto_scroll),
                    checked = settings.autoScrollLog,
                    onChange = onAutoScroll
                )
                SwitchRow(
                    label = stringResource(R.string.settings_hex_send),
                    checked = settings.hexSendMode,
                    onChange = onHexSend
                )
                Text(stringResource(R.string.settings_dark_theme), style = MaterialTheme.typography.labelLarge)
                ThemeChips(current = settings.darkTheme, onChange = onTheme)
                Text(stringResource(R.string.settings_terminator), style = MaterialTheme.typography.labelLarge)
                LineEndingChips(current = settings.lineEnding, onChange = onLineEnding)
                Text(
                    stringResource(R.string.settings_sample_rate),
                    style = MaterialTheme.typography.labelLarge
                )
                SampleRateChips(current = settings.sampleRateHz, onChange = onSampleRate)
                Text(
                    stringResource(R.string.settings_accel_sens),
                    style = MaterialTheme.typography.labelLarge
                )
                AccelSensitivityField(
                    current = settings.accelSensitivityLsbPerG,
                    onChange = onAccelSensitivity
                )
                // Профили плат — отдельный диалог. Здесь summary активного + кнопка.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Профили плат", style = MaterialTheme.typography.labelLarge)
                        Text(
                            settings.activeProfile?.let { "активен: $it" }
                                ?: "не выбран (${settings.boardProfiles.size} сохранено)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenProfiles) { Text("Профили") }
                }
                // Кастомные команды — отдельный диалог-редактор, открываемый
                // отсюда. Здесь только summary + кнопка.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_quick_commands),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.quick_cmd_summary, settings.quickCommands.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onEditQuickCommands) {
                        Text(stringResource(R.string.quick_cmd_edit))
                    }
                }
            }
        }
    )
}

/**
 * Редактор кастомных быстрых команд.
 *
 * UX: список с inline-редактированием (label, payload, HEX toggle) +
 * кнопка удалить (мини-крестик). Внизу — "Добавить", "По умолчанию",
 * "Сохранить", "Отмена". Изменения держим в локальном state и публикуем
 * в DataStore только по "Сохранить" — даёт пользователю отмену.
 */
@Composable
private fun QuickCommandsDialog(
    initial: List<com.example.connectapp.data.settings.QuickCommand>,
    onSave: (List<com.example.connectapp.data.settings.QuickCommand>) -> Unit,
    onDismiss: () -> Unit
) {
    var edited by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // Отфильтруем пустые строки — иначе будут чипы-призраки.
                val cleaned = edited.filter { it.label.isNotBlank() && it.payload.isNotBlank() }
                onSave(cleaned)
                onDismiss()
            }) { Text(stringResource(R.string.quick_cmd_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    edited = com.example.connectapp.data.settings.QuickCommand.DEFAULT
                }) { Text(stringResource(R.string.quick_cmd_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.quick_cmd_cancel)) }
            }
        },
        title = { Text(stringResource(R.string.quick_cmd_title)) },
        text = {
            Column {
                if (edited.isEmpty()) {
                    Text(
                        stringResource(R.string.quick_cmd_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        edited.forEachIndexed { i, cmd ->
                            QuickCommandRow(
                                cmd = cmd,
                                onChange = { newCmd ->
                                    edited = edited.toMutableList().also { it[i] = newCmd }
                                },
                                onDelete = {
                                    edited = edited.toMutableList().also { it.removeAt(i) }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        edited = edited + com.example.connectapp.data.settings.QuickCommand("new", "")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.quick_cmd_add))
                }
            }
        }
    )
}

/**
 * Профили плат: сохранить текущие настройки под именем, применить сохранённый
 * профиль (записывает его параметры в активные настройки), удалить.
 */
@Composable
private fun ProfilesDialog(
    profiles: List<com.example.connectapp.data.settings.BoardProfile>,
    activeProfile: String?,
    onSaveCurrent: (String) -> Unit,
    onApply: (com.example.connectapp.data.settings.BoardProfile) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Профили плат") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Профиль хранит терминатор, частоту опроса, чувствительность и " +
                        "быстрые команды. Сохраните текущие настройки под именем и " +
                        "переключайтесь между платами одним тапом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("имя профиля") },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { if (newName.isNotBlank()) { onSaveCurrent(newName); newName = "" } },
                        enabled = newName.isNotBlank()
                    ) { Text("Сохранить") }
                }
                if (profiles.isEmpty()) {
                    Text(
                        "Профилей пока нет — сохраните текущие настройки выше.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    profiles.forEach { p ->
                        val active = p.name == activeProfile
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    if (active) "${p.name}  ✓ активен" else p.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${p.lineEnding.name} · ${p.sampleRateHz.toInt()} Гц · " +
                                        "${p.accelSensitivityLsbPerG.toInt()} LSB/g · " +
                                        "команд: ${p.quickCommands.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onApply(p) }) { Text("Применить") }
                                    TextButton(onClick = { onDelete(p.name) }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun QuickCommandRow(
    cmd: com.example.connectapp.data.settings.QuickCommand,
    onChange: (com.example.connectapp.data.settings.QuickCommand) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = cmd.label,
            onValueChange = { onChange(cmd.copy(label = it)) },
            label = { Text(stringResource(R.string.quick_cmd_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedTextField(
            value = cmd.payload,
            onValueChange = { onChange(cmd.copy(payload = it)) },
            label = { Text(stringResource(R.string.quick_cmd_payload)) },
            modifier = Modifier.weight(1.3f),
            singleLine = true
        )
        FilterChip(
            selected = cmd.hex,
            onClick = { onChange(cmd.copy(hex = !cmd.hex)) },
            label = { Text(stringResource(R.string.quick_cmd_hex)) }
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.quick_cmd_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CalibrationDialog(
    log: String,
    running: Boolean,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onStart, enabled = !running) {
                Text(if (running) stringResource(R.string.calib_starting) else stringResource(R.string.btn_calibrate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calib_close)) }
        },
        title = { Text(stringResource(R.string.calib_title)) },
        text = {
            Column {
                Text(
                    "Положите плату горизонтально и нажмите «Калибровка». " +
                        "Команда «2» уйдёт на плату, ответ — ниже.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 200.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(
                        text = log.ifEmpty { "— нет ответа —" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    )
}

@Composable
private fun LineEndingChips(current: LineEnding, onChange: (LineEnding) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeChip(stringResource(R.string.term_lf), selected = current == LineEnding.LF) { onChange(LineEnding.LF) }
        ThemeChip(stringResource(R.string.term_cr), selected = current == LineEnding.CR) { onChange(LineEnding.CR) }
        ThemeChip(stringResource(R.string.term_crlf), selected = current == LineEnding.CRLF) { onChange(LineEnding.CRLF) }
        ThemeChip(stringResource(R.string.term_none), selected = current == LineEnding.NONE) { onChange(LineEnding.NONE) }
    }
}

/**
 * Чипы выбора частоты опроса. 5 пресетов: 1/5/10/20/50 Гц.
 * Совпадение по `current ≈ value` через малую epsilon — Float сравнение.
 */
@Composable
private fun SampleRateChips(current: Float, onChange: (Float) -> Unit) {
    val rates = listOf(1f, 5f, 10f, 20f, 50f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rates.forEach { rate ->
            ThemeChip(
                label = "${rate.toInt()} Гц",
                selected = kotlin.math.abs(current - rate) < 0.01f,
                onClick = { onChange(rate) }
            )
        }
    }
}

/**
 * Поле чувствительности акселерометра (LSB на 1g). Локальный текст, при валидном
 * положительном числе сразу персистится. Инициализируется текущим значением при
 * открытии диалога (не key на current — чтобы не перебивать ввод на каждом emit).
 */
@Composable
private fun AccelSensitivityField(current: Float, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(if (current % 1f == 0f) current.toInt().toString() else current.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.replace(',', '.').toFloatOrNull()?.let { if (it > 0f) onChange(it) }
        },
        singleLine = true,
        label = { Text("LSB на 1g") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ThemeChips(current: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeChip(stringResource(R.string.settings_theme_system), selected = current == null) { onChange(null) }
        ThemeChip(stringResource(R.string.settings_theme_light), selected = current == false) { onChange(false) }
        ThemeChip(stringResource(R.string.settings_theme_dark), selected = current == true) { onChange(true) }
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
                    data.temperature1?.let {
                        SensorTile(Icons.Filled.Thermostat, "T1", "%.1f°C".format(Locale.ROOT, it))
                    }
                    data.temperature2?.let {
                        SensorTile(Icons.Filled.Thermostat, "T2", "%.1f°C".format(Locale.ROOT, it))
                    }
                }
            }
            if (data.hasAnyAccel) {
                // Каждый акселерометр на своей строке — иначе 6 чисел не помещаются.
                if (data.accel1X != null || data.accel1Y != null || data.accel1Z != null) {
                    SensorTile(
                        Icons.Filled.Tune, "A1",
                        "%+5.0f  %+5.0f  %+5.0f".format(
                            Locale.ROOT,
                            data.accel1X ?: 0f, data.accel1Y ?: 0f, data.accel1Z ?: 0f
                        )
                    )
                }
                if (data.accel2X != null || data.accel2Y != null || data.accel2Z != null) {
                    SensorTile(
                        Icons.Filled.Tune, "A2",
                        "%+5.0f  %+5.0f  %+5.0f".format(
                            Locale.ROOT,
                            data.accel2X ?: 0f, data.accel2Y ?: 0f, data.accel2Z ?: 0f
                        )
                    )
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
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun HistoryRow(
    items: List<ConnectionHistoryEntry>,
    onSelect: (ConnectionHistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.history_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.history_clear))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { entry ->
                    AssistChip(
                        onClick = { onSelect(entry) },
                        label = { Text(entry.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<BluetoothDeviceItem>,
    onClick: (BluetoothDeviceItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        Box(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.label_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp)
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(devices, key = { it.address }) { device ->
            DeviceRow(device = device, onClick = { onClick(device) })
        }
    }
}

@Composable
private fun DeviceRow(device: BluetoothDeviceItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                device.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AssistChip(
            onClick = onClick,
            label = { Text(stringResource(if (device.bonded) R.string.device_bonded else R.string.device_found)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (device.bonded)
                    MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun CommandChips(
    commands: List<com.example.connectapp.data.settings.QuickCommand>,
    enabled: Boolean,
    onCommand: (com.example.connectapp.data.settings.QuickCommand) -> Unit
) {
    if (commands.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        commands.forEach { cmd ->
            AssistChip(
                onClick = { onCommand(cmd) },
                enabled = enabled,
                label = {
                    // Префикс "▮ " (квадратик) для HEX-команд — визуально отличает
                    // от обычных текстовых.
                    Text(if (cmd.hex) "▮ ${cmd.label}" else cmd.label)
                }
            )
        }
    }
}
