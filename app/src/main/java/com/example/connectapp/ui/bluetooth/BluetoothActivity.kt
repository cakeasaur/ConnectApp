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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Text
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.ui.theme.ConnectAppTheme
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.ui.wifi.StatusBadge
import com.example.connectapp.utils.PermissionHelper

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
            ConnectAppTheme {
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

    var payload by remember { mutableStateOf("") }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.toast_bluetooth_off), Toast.LENGTH_LONG).show()
            (ctx as? ComponentActivity)?.finish()
        }
    }

    fun ensureBluetoothEnabled() {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
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

    // При первом запуске — проверить и запросить разрешения.
    LaunchedEffect(Unit) {
        val needed = PermissionHelper.missing(ctx, PermissionHelper.bluetoothPermissions())
        if (needed.isEmpty()) ensureBluetoothEnabled() else permissionLauncher.launch(needed)
    }

    // Toast про ошибки.
    LaunchedEffect(state) {
        if (state is ConnectionState.Error) {
            Toast.makeText(ctx, (state as ConnectionState.Error).message, Toast.LENGTH_LONG).show()
        }
    }

    val connected = state is ConnectionState.Connected

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
            StatusBadge(state = state)

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

            // Быстрые команды
            CommandChips(enabled = connected, onCommand = { viewModel.send(it) })

            // Отправка свободного сообщения
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
                Row {
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

            LogView(log = log, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SensorPanel(data: SensorData) {
    val hasTemp = data.temperature != null
    val hasAccel = data.accelX != null || data.accelY != null || data.accelZ != null
    if (!hasTemp && !hasAccel) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasTemp) {
                SensorTile(
                    icon = Icons.Filled.Thermostat,
                    label = stringResource(R.string.label_temperature),
                    value = "%.1f°C".format(java.util.Locale.ROOT, data.temperature)
                )
            }
            if (hasAccel) {
                SensorTile(
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.label_accelerometer),
                    value = "X:%.1f  Y:%.1f  Z:%.1f".format(
                        java.util.Locale.ROOT,
                        data.accelX ?: 0f,
                        data.accelY ?: 0f,
                        data.accelZ ?: 0f
                    )
                )
            }
        }
    }
}

@Composable
private fun SensorTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
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
    val commands = listOf(
        stringResource(R.string.cmd_help),
        stringResource(R.string.cmd_status),
        stringResource(R.string.cmd_temp),
        stringResource(R.string.cmd_accel),
        stringResource(R.string.cmd_time),
        stringResource(R.string.cmd_version),
        stringResource(R.string.cmd_relay),
        stringResource(R.string.cmd_monitor)
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
