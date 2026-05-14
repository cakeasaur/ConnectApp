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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.LineEnding
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.ui.theme.ConnectAppTheme
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.ui.wifi.StatusBadge
import com.example.connectapp.utils.PermissionHelper
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
                    onOpenGraphs = { startActivity(Intent(this, GraphActivity::class.java)) }
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

    var payload by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var hexMode by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

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
    ) { result ->
        val granted = result.isNotEmpty() && result.values.all { it }
        if (granted) {
            ensureBluetoothEnabled()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.toast_bluetooth_perms_required), Toast.LENGTH_LONG).show()
            (ctx as? ComponentActivity)?.finish()
        }
    }

    LaunchedEffect(Unit) {
        val needed = PermissionHelper.missing(ctx, PermissionHelper.bluetoothPermissions())
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
            onLineEnding = viewModel::setLineEnding
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btn_bluetooth)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    RecordingButton(
                        isRecording = isRecording,
                        onStart = {
                            val f = viewModel.startRecording()
                            if (f != null) Toast.makeText(ctx, ctx.getString(R.string.rec_started, f.name), Toast.LENGTH_SHORT).show()
                        },
                        onStop = {
                            val f = viewModel.stopRecording() ?: recordingFile
                            if (f != null) {
                                Toast.makeText(ctx, ctx.getString(R.string.rec_stopped, f.name), Toast.LENGTH_LONG).show()
                                shareRecording(ctx, f)
                            }
                        }
                    )
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
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
            StatusBadge(state = state)
            HeartbeatRow(state = state, lastPacketAt = lastPacketAt)

            SensorPanel(data = sensor)

            // Поиск + список устройств
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
                        onClick = { viewModel.connect(it.address) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 220.dp)
                    )
                }
            }

            CommandChips(enabled = connected, onCommand = { viewModel.send(it) })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text(stringResource(R.string.hint_payload)) },
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
    if (!hexMode) return filtered
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

@Composable
private fun RecordingButton(
    isRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    IconButton(onClick = { if (isRecording) onStop() else onStart() }) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = stringResource(if (isRecording) R.string.rec_stop else R.string.rec_start),
            tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HeartbeatRow(state: ConnectionState, lastPacketAt: Long?) {
    if (state !is ConnectionState.Connected || lastPacketAt == null) return
    // Перетриггер каждую секунду чтобы отображение возраста обновлялось.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
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
    val warn = ageMs > 5_000
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
    onLineEnding: (LineEnding) -> Unit
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
                Text(stringResource(R.string.settings_dark_theme), style = MaterialTheme.typography.labelLarge)
                ThemeChips(current = settings.darkTheme, onChange = onTheme)
                Text(stringResource(R.string.settings_terminator), style = MaterialTheme.typography.labelLarge)
                LineEndingChips(current = settings.lineEnding, onChange = onLineEnding)
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
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (data.accel1X != null || data.accel1Y != null || data.accel1Z != null) {
                        SensorTile(
                            Icons.Filled.Tune, "A1",
                            "X:%.1f Y:%.1f Z:%.1f".format(
                                Locale.ROOT,
                                data.accel1X ?: 0f, data.accel1Y ?: 0f, data.accel1Z ?: 0f
                            )
                        )
                    }
                    if (data.accel2X != null || data.accel2Y != null || data.accel2Z != null) {
                        SensorTile(
                            Icons.Filled.Tune, "A2",
                            "X:%.1f Y:%.1f Z:%.1f".format(
                                Locale.ROOT,
                                data.accel2X ?: 0f, data.accel2Y ?: 0f, data.accel2Z ?: 0f
                            )
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
private fun CommandChips(enabled: Boolean, onCommand: (String) -> Unit) {
    // Команды под прошивку PIC24FJ128GB106 Sensor Monitor:
    //   меню boot — '1'/'2'/'3'  (enhanced monitoring / calibrate / quick test)
    //   UART menu — echo / help / clear / freq / test
    val commands = listOf(
        stringResource(R.string.cmd_one),
        stringResource(R.string.cmd_two),
        stringResource(R.string.cmd_three),
        stringResource(R.string.cmd_help),
        stringResource(R.string.cmd_echo),
        stringResource(R.string.cmd_clear),
        stringResource(R.string.cmd_freq),
        stringResource(R.string.cmd_test)
    )
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
                label = { Text(cmd) }
            )
        }
    }
}
