package com.example.connectapp.ui.usb

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.ui.theme.AppThemeWithSettings

/**
 * Экран USB serial. Лёгкая версия BT-экрана без скана/истории/калибровки.
 * Сценарий: воткнул OTG-кабель в плату → "Обновить" → выбрал устройство →
 * "Подключить" → системный диалог разрешения → сидишь смотришь поток.
 */
class UsbSerialActivity : ComponentActivity() {

    private val viewModel: UsbSerialViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                UsbScreen(
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
private fun UsbScreen(
    viewModel: UsbSerialViewModel,
    onBack: () -> Unit,
    onOpenGraphs: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    var payload by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshDevices() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usb_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGraphs) {
                        Icon(
                            Icons.Filled.Insights,
                            contentDescription = stringResource(R.string.btn_graphs)
                        )
                    }
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.btn_scan)
                        )
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
            // Статус подключения
            StatusCard(state = state)

            // Устройства
            Text(stringResource(R.string.label_devices), style = MaterialTheme.typography.titleMedium)
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.usb_no_devices),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    devices.forEach { device ->
                        DeviceCard(
                            device = device,
                            connected = state is ConnectionState.Connected,
                            onConnect = { viewModel.connect(device) }
                        )
                    }
                }
            }

            // Disconnect button — отдельно, если подключены
            if (state is ConnectionState.Connected) {
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_disconnect))
                }
            }

            // Send row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text(stringResource(R.string.hint_payload)) },
                    enabled = state is ConnectionState.Connected,
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (payload.isNotBlank()) { viewModel.send(payload); payload = "" }
                    },
                    enabled = state is ConnectionState.Connected && payload.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
            }

            // Log
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.label_log),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.btn_clear))
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    BasicTextField(
                        value = log.ifEmpty { stringResource(R.string.label_no_data) },
                        onValueChange = {},
                        readOnly = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Idle -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Connected -> stringResource(R.string.status_connected) to MaterialTheme.colorScheme.primary
        is ConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting, state.attempt) to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Disconnected -> stringResource(R.string.status_disconnected) to MaterialTheme.colorScheme.error
        is ConnectionState.Error -> stringResource(R.string.status_error, state.message) to MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Usb, contentDescription = null, tint = color)
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceCard(
    device: UsbDevice,
    connected: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.productName ?: device.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "VID:%04X PID:%04X · %s".format(
                        device.vendorId,
                        device.productId,
                        device.deviceName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!connected) {
                Button(onClick = onConnect) {
                    Icon(Icons.Filled.Link, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_connect))
                }
            } else {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.status_connected)) }
                )
            }
        }
    }
}
