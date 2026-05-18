package com.example.connectapp.ui.mqtt

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.connectapp.R
import com.example.connectapp.data.mqtt.MqttBridge
import com.example.connectapp.data.mqtt.MqttConfig
import com.example.connectapp.data.mqtt.MqttState
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.theme.ErrorRed
import com.example.connectapp.ui.theme.SuccessGreen
import com.example.connectapp.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MqttSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app.applicationContext)

    private val _config = MutableStateFlow(MqttConfig.DEFAULT)
    val config: StateFlow<MqttConfig> = _config.asStateFlow()

    val state: StateFlow<MqttState> = MqttBridge.state

    init {
        viewModelScope.launch {
            _config.value = repo.mqttConfig.first()
        }
    }

    fun save(cfg: MqttConfig) {
        _config.value = cfg
        viewModelScope.launch { repo.setMqttConfig(cfg) }
    }

    fun reconnect() = MqttBridge.reconnectNow()
}

class MqttSettingsActivity : ComponentActivity() {
    private val vm: MqttSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                MqttSettingsScreen(vm = vm, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MqttSettingsScreen(vm: MqttSettingsViewModel, onBack: () -> Unit) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    // Local form-state — позволяет редактировать без авто-сохранения на каждый
    // keystroke (что вызывало бы reconnect-шторм). Применяется по кнопке.
    var enabled by remember(cfg.enabled) { mutableStateOf(cfg.enabled) }
    var host by remember(cfg.host) { mutableStateOf(cfg.host) }
    var port by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
    var tls by remember(cfg.useTls) { mutableStateOf(cfg.useTls) }
    var trustAll by remember(cfg.trustAllCerts) { mutableStateOf(cfg.trustAllCerts) }
    var username by remember(cfg.username) { mutableStateOf(cfg.username) }
    var password by remember(cfg.password) { mutableStateOf(cfg.password) }
    var clientId by remember(cfg.clientId) { mutableStateOf(cfg.clientId) }
    var prefix by remember(cfg.topicPrefix) { mutableStateOf(cfg.topicPrefix) }
    var qos by remember(cfg.publishQos) { mutableStateOf(cfg.publishQos.toString()) }
    var retained by remember(cfg.publishRetained) { mutableStateOf(cfg.publishRetained) }
    var throttle by remember(cfg.throttleMs) { mutableStateOf(cfg.throttleMs.toString()) }
    var subscribeCmds by remember(cfg.subscribeCommands) { mutableStateOf(cfg.subscribeCommands) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mqtt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reconnect() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mqtt_reconnect))
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MqttStatusBadge(state = state)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SwitchRow(stringResource(R.string.mqtt_enabled), enabled) { enabled = it }
                    // Inline validation. host пустой допустим (отключено), но если
                    // включено и пусто — error. port проверяем всегда.
                    val hostError = enabled && host.isBlank()
                    val parsedPort = port.toIntOrNull()
                    val portError = port.isNotEmpty() && (parsedPort == null || parsedPort !in 1..65535)
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        label = { Text(stringResource(R.string.mqtt_host)) },
                        singleLine = true,
                        isError = hostError,
                        supportingText = if (hostError) {
                            @Composable { Text(stringResource(R.string.err_host_blank)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.mqtt_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = portError,
                        supportingText = if (portError) {
                            @Composable { Text(stringResource(R.string.err_port_range)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(stringResource(R.string.mqtt_tls), tls) { tls = it }
                    AnimatedVisibility(
                        visible = tls,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SwitchRow(stringResource(R.string.mqtt_trust_all), trustAll) { trustAll = it }
                            AnimatedVisibility(
                                visible = trustAll,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    stringResource(R.string.mqtt_trust_all_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ErrorRed
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.mqtt_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.mqtt_pass)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(stringResource(R.string.mqtt_client_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = { prefix = it.trim().trimEnd('/') },
                        label = { Text(stringResource(R.string.mqtt_prefix)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = qos,
                        onValueChange = { qos = it.filter { c -> c.isDigit() }.take(1) },
                        label = { Text(stringResource(R.string.mqtt_qos)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(stringResource(R.string.mqtt_retained), retained) { retained = it }
                    OutlinedTextField(
                        value = throttle,
                        onValueChange = { throttle = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.mqtt_throttle)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(stringResource(R.string.mqtt_sub_cmd), subscribeCmds) { subscribeCmds = it }
                    AnimatedVisibility(
                        visible = subscribeCmds,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            stringResource(R.string.mqtt_sub_cmd_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningAmber
                        )
                    }
                }
            }

            Button(
                onClick = {
                    vm.save(
                        MqttConfig(
                            enabled = enabled,
                            host = host,
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 1883,
                            useTls = tls,
                            trustAllCerts = trustAll,
                            username = username,
                            password = password,
                            clientId = clientId,
                            topicPrefix = prefix.ifBlank { "connect" },
                            publishQos = qos.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                            publishRetained = retained,
                            throttleMs = throttle.toIntOrNull()?.coerceAtLeast(0) ?: 100,
                            subscribeCommands = subscribeCmds,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.mqtt_apply))
            }

            TopicsHelp(prefix.ifBlank { "connect" })
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun MqttStatusBadge(state: MqttState) {
    // Crossfade — плавная замена цвета/текста между состояниями. Без него
    // переход Connecting → Connected визуально дёрнутый: цвет меняется
    // мгновенно одним кадром.
    Crossfade(targetState = state, label = "mqtt-status") { s ->
        val (label, color) = when (s) {
            MqttState.Disabled -> stringResource(R.string.mqtt_status_disabled) to MaterialTheme.colorScheme.onSurfaceVariant
            MqttState.Idle -> stringResource(R.string.mqtt_status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
            MqttState.Connecting -> stringResource(R.string.mqtt_status_connecting) to WarningAmber
            MqttState.Connected -> stringResource(R.string.mqtt_status_connected) to SuccessGreen
            is MqttState.Error -> stringResource(R.string.mqtt_status_error, s.message) to ErrorRed
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
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TopicsHelp(prefix: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.mqtt_topics_title), style = MaterialTheme.typography.titleSmall)
            Text(
                "OUT: $prefix/temp1, $prefix/temp2\n" +
                    "     $prefix/accel1, $prefix/accel2\n" +
                    "     $prefix/status (retained)\n" +
                    "IN:  $prefix/cmd  →  forward to active transport",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
