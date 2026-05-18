package com.example.connectapp.ui.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.GlobalConnectionStatus
import com.example.connectapp.data.repository.WifiRepository
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.service.ConnectionForegroundService
import com.example.connectapp.utils.Constants
import com.example.connectapp.utils.LogBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WifiViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repo = WifiRepository()
    private val settingsRepo = SettingsRepository(application.applicationContext)
    @Volatile private var settings: AppSettings = AppSettings.DEFAULT

    val state: StateFlow<ConnectionState> = repo.state

    /** Аккумулированный лог, переживающий смену конфигурации. */
    private val logBuffer = LogBuffer(initial = handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = logBuffer.text

    private var saveLogJob: Job? = null

    var host: String
        get() = handle.get<String>(KEY_HOST).orEmpty()
        set(value) { handle[KEY_HOST] = value }

    var port: String
        get() = handle.get<String>(KEY_PORT).orEmpty()
        set(value) { handle[KEY_PORT] = value }

    init {
        viewModelScope.launch {
            settingsRepo.flow.collect { settings = it }
        }
        viewModelScope.launch {
            repo.incoming.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                scheduleSave()
            }
        }
        // Команды из MQTT/GraphActivity — отправляем на плату только если
        // мы сейчас активный коннект (Connected). Иначе игнорируем — другой
        // VM (BT/USB) тоже слушает, пусть он и отвечает.
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                if (state.value is ConnectionState.Connected) sendSilent(cmd)
            }
        }
        // Глобальный статус для banner'а на MainActivity.
        viewModelScope.launch {
            state.collect { s ->
                GlobalConnectionStatus.set(
                    transport = FG_OWNER,
                    snapshot = GlobalConnectionStatus.Snapshot(
                        state = s,
                        label = "${host}:${port}".takeIf { host.isNotBlank() } ?: "—"
                    )
                )
            }
        }
        // Foreground service на жизненном цикле Connected. Запускаем
        // именно на переходе not-Connected → Connected, чтобы при
        // мерцании состояния не было лишних старт/стопов.
        viewModelScope.launch {
            var wasConnected = false
            state.collect { s ->
                val nowConnected = s is ConnectionState.Connected
                val transition = nowConnected && !wasConnected
                wasConnected = nowConnected
                if (transition) {
                    val title = application.getString(
                        com.example.connectapp.R.string.fg_title_wifi,
                        "${host}:${port}"
                    )
                    ConnectionForegroundService.start(application, FG_OWNER, title)
                } else if (!nowConnected) {
                    ConnectionForegroundService.stop(application, FG_OWNER)
                }
            }
        }
    }

    fun connect(host: String, port: Int) {
        // Defense-in-depth: UI уже валидирует (WifiActivity), но без проверки
        // здесь любой каллер VM мог бы передать "" / -1 и упасть глубже в стеке.
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) {
            logBuffer.appendLine(
                "← [ERROR] invalid address: '$host:$port'",
                viewModelScope
            )
            return
        }
        this.host = cleanHost
        this.port = port.toString()
        viewModelScope.launch {
            repo.connect(cleanHost, port, viewModelScope)
        }
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    fun send(payload: String) {
        logBuffer.appendLine("→ $payload", viewModelScope)
        scheduleSave()
        val terminated = payload + settings.lineEnding.suffix
        viewModelScope.launch {
            repo.send(terminated).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    /** Тихая отправка (без префикса «→» в лог) — для MQTT/CommandBus-команд. */
    private fun sendSilent(payload: String) {
        val terminated = payload + settings.lineEnding.suffix
        viewModelScope.launch {
            repo.send(terminated).onFailure { e ->
                logBuffer.appendLine("← [ERROR] mqtt: ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    fun clearLog() {
        saveLogJob?.cancel()
        logBuffer.clear()
        handle[KEY_LOG] = ""
    }

    /** Сохраняем лог в SavedStateHandle с debounce — сериализация в Bundle дорогая. */
    private fun scheduleSave() {
        saveLogJob?.cancel()
        saveLogJob = viewModelScope.launch {
            delay(Constants.LOG_SAVE_DEBOUNCE_MS)
            logBuffer.flush()
            handle[KEY_LOG] = logBuffer.text.value
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveLogJob?.cancel()
        logBuffer.flush()
        handle[KEY_LOG] = logBuffer.text.value
        ConnectionForegroundService.stop(getApplication(), FG_OWNER)
        GlobalConnectionStatus.set(FG_OWNER, null)
    }

    companion object {
        private const val KEY_HOST = "wifi.host"
        private const val KEY_PORT = "wifi.port"
        const val KEY_LOG = "wifi.log"
        private const val FG_OWNER = "wifi"
    }
}
