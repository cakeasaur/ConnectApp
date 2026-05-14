package com.example.connectapp.ui.wifi

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.theme.ErrorRed
import com.example.connectapp.ui.theme.SuccessGreen
import com.example.connectapp.ui.theme.WarningAmber
import kotlinx.coroutines.delay

class WifiActivity : ComponentActivity() {

    private val viewModel: WifiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                WifiScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiScreen(viewModel: WifiViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var host by remember { mutableStateOf(viewModel.host) }
    var port by remember { mutableStateOf(viewModel.port) }
    var payload by remember { mutableStateOf("") }

    // Toast при ошибках/потерях соединения + автоматический возврат на меню (как обещает README).
    LaunchedEffect(state) {
        when (val s = state) {
            is ConnectionState.Error -> {
                Toast.makeText(ctx, ctx.getString(R.string.toast_error, s.message), Toast.LENGTH_LONG).show()
                delay(1500)
                onBack()
            }
            is ConnectionState.Disconnected -> {
                Toast.makeText(ctx, ctx.getString(R.string.toast_connection_lost), Toast.LENGTH_SHORT).show()
                delay(1500)
                onBack()
            }
            else -> Unit
        }
    }

    val connected = state is ConnectionState.Connected
    val connecting = state is ConnectionState.Connecting || state is ConnectionState.Reconnecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btn_wifi)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
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

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.hint_ip)) },
                        singleLine = true,
                        enabled = !connected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.hint_port)) },
                        singleLine = true,
                        enabled = !connected,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    Toast.makeText(ctx, ctx.getString(R.string.toast_invalid_address), Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.connect(host.trim(), p)
                                }
                            },
                            enabled = !connected && !connecting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_connect))
                        }
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            enabled = connected || connecting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.LinkOff, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_disconnect))
                        }
                    }
                }
            }

            // Отправка сообщения
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
                Text(
                    text = stringResource(R.string.label_log),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.btn_clear_log))
                }
            }

            LogView(log = log, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun StatusBadge(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Idle -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting) to WarningAmber
        is ConnectionState.Connected -> stringResource(R.string.status_connected) to SuccessGreen
        is ConnectionState.Disconnected -> stringResource(R.string.status_disconnected) to ErrorRed
        is ConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting, state.attempt) to WarningAmber
        is ConnectionState.Error -> stringResource(R.string.status_error, state.message) to ErrorRed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun LogView(log: String, modifier: Modifier = Modifier, autoScroll: Boolean = true) {
    val scroll = rememberScrollState()
    // Автоскролл при добавлении новых строк (если включён в настройках).
    LaunchedEffect(log, autoScroll) {
        if (autoScroll) {
            delay(30)
            scroll.scrollTo(scroll.maxValue)
        }
    }
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = if (log.isEmpty()) stringResource(R.string.label_no_data) else log,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.verticalScroll(scroll)
        )
    }
}
