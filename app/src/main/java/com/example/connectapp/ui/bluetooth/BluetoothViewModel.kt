package com.example.connectapp.ui.bluetooth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.CommandLog
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.GlobalConnectionStatus
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.repository.BluetoothRepository
import com.example.connectapp.data.settings.AppSettings
import com.example.connectapp.data.settings.ConnectionHistoryEntry
import com.example.connectapp.data.settings.LineEnding
import com.example.connectapp.data.settings.QuickCommand
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.service.ConnectionForegroundService
import com.example.connectapp.utils.Constants
import com.example.connectapp.utils.DataParser
import com.example.connectapp.utils.HexCodec
import com.example.connectapp.utils.LogBuffer
import com.example.connectapp.utils.SessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.File

class BluetoothViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repo = BluetoothRepository(application.applicationContext)
    private val settingsRepo = SettingsRepository(application.applicationContext)
    private val recorder = SessionRecorder(application.applicationContext)

    val state: StateFlow<ConnectionState> = repo.state
    val devices: StateFlow<List<BluetoothDeviceItem>> = repo.devices
    val scanning: StateFlow<Boolean> = repo.scanning
    val lastPacketAt: StateFlow<Long?> = repo.lastPacketAt

    val isRecording: StateFlow<Boolean> = recorder.isRecording
    val recordingFile: StateFlow<File?> = recorder.currentFile

    private val logBuffer = LogBuffer(initial = handle.get<String>(KEY_LOG).orEmpty())
    val log: StateFlow<String> = logBuffer.text

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings.DEFAULT)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    var lastAddress: String?
        get() = handle.get<String>(KEY_ADDRESS)
        set(value) { handle[KEY_ADDRESS] = value }

    private val lineBuffer = StringBuilder()
    private var saveLogJob: Job? = null
    private var autoReconnectTried = false

    init {
        // Сохраняем настройки в свойство для синхронного доступа.
        viewModelScope.launch {
            settingsRepo.flow.collect { _settings.value = it }
        }

        viewModelScope.launch {
            repo.incoming.collect { chunk ->
                logBuffer.appendRaw(chunk, viewModelScope)
                scheduleSave()
                recorder.appendChunk(chunk) // уже сам уходит на IO-scope.
                // Парсер с регэкспами — CPU-bound, не блокируем UI-поток.
                withContext(Dispatchers.Default) { parseChunk(chunk) }
            }
        }
        // Forward commands posted from GraphActivity (or any other screen).
        // Фильтр по target: команда с target=null — broadcast (MQTT),
        // реагируем; target="bt" — адресовано нам, реагируем; любое
        // другое значение — игнор (раньше тут шла любая команда, что
        // ломало сценарий «BT + USB одновременно подключены»).
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                val forUs = cmd.target == null || cmd.target == FG_OWNER
                if (forUs && state.value is ConnectionState.Connected) {
                    sendSilent(cmd.payload)
                }
            }
        }
        // Auto-monitor + foreground service: при ПЕРЕХОДЕ в Connected
        // запускаем оба эффекта одним watcher'ом. Foreground service
        // держит процесс живым (иначе при сворачивании Android убьёт BT-
        // соединение через ~2 мин). Останавливаем когда становимся
        // не-Connected (Idle/Error/Reconnecting/Disconnected).
        //
        // Auto-monitor шлёт '1' только при ПЕРЕХОДЕ not-Connected → Connected
        // (не на каждое появление Connected), иначе при auto-reconnect цикле
        // PIC firmware получает '1' дважды и интерпретирует второе как stop.
        // Глобальный статус — для persistent-banner'а на MainActivity.
        viewModelScope.launch {
            state.collect { s ->
                GlobalConnectionStatus.set(
                    transport = FG_OWNER,
                    snapshot = GlobalConnectionStatus.Snapshot(
                        state = s,
                        label = lastAddress ?: "—"
                    )
                )
            }
        }
        viewModelScope.launch {
            var wasConnected = false
            state.collect { s ->
                val nowConnected = s is ConnectionState.Connected
                val transition = nowConnected && !wasConnected
                wasConnected = nowConnected
                if (transition) {
                    // Запуск foreground service.
                    val title = application.getString(
                        com.example.connectapp.R.string.fg_title_bt,
                        lastAddress ?: "—"
                    )
                    ConnectionForegroundService.start(application, FG_OWNER, title)
                    if (_settings.value.autoMonitor) {
                        // Задержка чтобы плата успела выпустить boot-меню и быть в режиме приёма.
                        delay(300)
                        sendSilent(application.getString(com.example.connectapp.R.string.cmd_one))
                    }
                } else if (!nowConnected) {
                    // Остановка fg-service при любом не-Connected состоянии,
                    // включая Reconnecting — иначе пользователь видит "Соединение
                    // активно" пока на самом деле уже разорвано.
                    ConnectionForegroundService.stop(application, FG_OWNER)
                }
            }
        }
    }

    fun isAvailable() = repo.isAvailable()
    fun isEnabled() = repo.isEnabled()

    fun loadBonded() {
        repo.stopDiscovery()
        repo.refreshBonded()
    }

    fun startDiscovery() = repo.startDiscovery()
    fun stopDiscovery() = repo.stopDiscovery()

    fun connect(address: String, name: String? = null) {
        lastAddress = address
        // Чистим SensorDataBus от старой сессии: иначе графики покажут
        // склейку «доска A → доска B» с монотонно растущими timestamps,
        // и пользователь подумает что это один непрерывный поток.
        SensorDataBus.clear()
        _sensorData.value = SensorData()
        viewModelScope.launch {
            repo.connect(address, viewModelScope)
        }
        // Запоминаем в истории (имя из bonded или из аргумента, или сам MAC).
        val displayName = name
            ?: repo.devices.value.firstOrNull { it.address == address }?.name
            ?: address
        viewModelScope.launch {
            settingsRepo.pushConnection(ConnectionHistoryEntry(displayName, address))
        }
    }

    /** Стрим истории подключений для UI. */
    val connectionHistory: StateFlow<List<ConnectionHistoryEntry>> =
        settingsRepo.connectionHistory.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun clearHistory() = viewModelScope.launch { settingsRepo.clearHistory() }

    /** Однократная попытка авто-реконнекта при первом запуске экрана. */
    fun maybeAutoReconnect() {
        if (autoReconnectTried) return
        autoReconnectTried = true
        viewModelScope.launch {
            val s = settingsRepo.flow.first()
            val addr = lastAddress
            if (s.autoReconnect && !addr.isNullOrBlank() && state.value is ConnectionState.Idle) {
                logBuffer.appendLine("[auto] подключаюсь к $addr…", viewModelScope)
                connect(addr)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    /**
     * Send a message from the free-text input.
     * Если в настройках включён HEX-режим — payload интерпретируется как HEX-байты
     * ("AA 55 01") и отправляется бинарно, без терминатора.
     */
    fun send(payload: String) {
        if (_settings.value.hexSendMode) {
            sendHex(payload); return
        }
        sendText(payload)
    }

    /**
     * Принудительно текстовая отправка с терминатором — для quick-chips и других
     * захардкоженных команд ('1', 'help', 'echo'). HEX-режим тут не применяется,
     * иначе '1' (нечётное число hex-цифр) проваливался бы парсингом.
     */
    fun sendText(payload: String) {
        logBuffer.appendLine("→ $payload", viewModelScope)
        scheduleSave()
        val terminated = payload + _settings.value.lineEnding.suffix
        viewModelScope.launch {
            repo.send(terminated).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    /** Шлёт ESC (0x1B) сырым байтом без терминатора — прошивка прерывает
     *  поток телеметрии по нему ("press escape" в boot-меню). */
    fun sendEscape() {
        logBuffer.appendLine("→ ESC (0x1B)", viewModelScope)
        scheduleSave()
        viewModelScope.launch {
            repo.sendBytes(byteArrayOf(0x1B)).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    private fun sendHex(input: String) {
        val bytes = HexCodec.parse(input)
        if (bytes == null) {
            val reason = HexCodec.describeError(input) ?: "invalid HEX"
            logBuffer.appendLine("← [ERROR] HEX $reason: $input", viewModelScope)
            scheduleSave()
            return
        }
        logBuffer.appendLine("→ HEX ${HexCodec.encode(bytes)}", viewModelScope)
        scheduleSave()
        viewModelScope.launch {
            repo.sendBytes(bytes).onFailure { e ->
                logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    private fun sendSilent(payload: String) {
        val terminated = payload + _settings.value.lineEnding.suffix
        viewModelScope.launch {
            repo.send(terminated).onFailure { e ->
                logBuffer.appendLine("← [ERROR] auto: ${e.message ?: "send failed"}", viewModelScope)
                scheduleSave()
            }
        }
    }

    fun clearLog() {
        saveLogJob?.cancel()
        logBuffer.clear()
        handle[KEY_LOG] = ""
        // lineBuffer читается из parseChunk на Dispatchers.Default — синхрон
        // обязателен, иначе ConcurrentModificationException.
        synchronized(lineBuffer) { lineBuffer.clear() }
        _sensorData.value = SensorData()
        SensorDataBus.clear()
    }

    fun startRecording(): File? = recorder.start()
    fun stopRecording(): File? = recorder.stop()

    fun setAutoReconnect(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoReconnect(value) }
    fun setAutoMonitor(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoMonitor(value) }
    fun setAutoScrollLog(value: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoScrollLog(value) }
    fun setDarkTheme(value: Boolean?) =
        viewModelScope.launch { settingsRepo.setDarkTheme(value) }
    fun setLineEnding(value: LineEnding) =
        viewModelScope.launch { settingsRepo.setLineEnding(value) }
    fun setHexSendMode(value: Boolean) =
        viewModelScope.launch { settingsRepo.setHexSendMode(value) }
    fun setSampleRateHz(value: Float) =
        viewModelScope.launch { settingsRepo.setSampleRateHz(value) }
    fun setQuickCommands(list: List<QuickCommand>) =
        viewModelScope.launch { settingsRepo.setQuickCommands(list) }

    /**
     * Отправить кастомную quick-команду. В отличие от [send], решение
     * "HEX или text" берётся ИЗ САМОЙ команды (cmd.hex), а не из глобальной
     * настройки hexSendMode. Это даёт смешанный набор: текстовые '1'/'help'
     * рядом с бинарным "AA 55 FF" — каждая отправляется как надо.
     */
    fun sendQuick(cmd: QuickCommand) {
        if (cmd.hex) {
            val bytes = HexCodec.parse(cmd.payload)
            if (bytes == null) {
                val reason = HexCodec.describeError(cmd.payload) ?: "invalid HEX"
                logBuffer.appendLine(
                    "← [ERROR] HEX in quick '${cmd.label}' ($reason): ${cmd.payload}",
                    viewModelScope
                )
                scheduleSave()
                return
            }
            logBuffer.appendLine("→ HEX [${cmd.label}] ${HexCodec.encode(bytes)}", viewModelScope)
            scheduleSave()
            viewModelScope.launch {
                repo.sendBytes(bytes).onFailure { e ->
                    logBuffer.appendLine("← [ERROR] ${e.message ?: "send failed"}", viewModelScope)
                    scheduleSave()
                }
            }
        } else {
            sendText(cmd.payload)
        }
    }

    /**
     * Калибровка акселерометров — отправляем команду '2' и слушаем 8 секунд
     * ответы платы. Используем StringBuilder + debounced StateFlow publish,
     * чтобы O(n²) String-конкат не упёрся в OOM на monitor-флуде.
     *
     * Если плата уже в monitor-режиме, в окно может прилететь CSV-поток. Это
     * не помеха — пользователь видит сырой raw, может вручную найти строку
     * 'Calibration complete'. Можно было бы фильтровать по ключевым словам,
     * но без знания вариантов прошивок это рискованно.
     */
    private val _calibrationLog = MutableStateFlow("")
    val calibrationLog: StateFlow<String> = _calibrationLog.asStateFlow()
    private val _calibrationRunning = MutableStateFlow(false)
    val calibrationRunning: StateFlow<Boolean> = _calibrationRunning.asStateFlow()

    fun runCalibration() {
        if (_calibrationRunning.value) return
        if (state.value !is ConnectionState.Connected) {
            _calibrationLog.value = "Сначала подключитесь к плате"
            return
        }
        _calibrationRunning.value = true
        _calibrationLog.value = ""
        viewModelScope.launch {
            val buf = StringBuilder(8192)
            var publishJob: Job? = null
            val collector = launch {
                repo.incoming.collect { chunk ->
                    synchronized(buf) {
                        buf.append(chunk)
                        // Ограничиваем размер — на monitor-флуде не сожрём всю RAM.
                        val over = buf.length - 16_000
                        if (over > 0) buf.delete(0, over)
                    }
                    // Дебаунс публикации в StateFlow — не дёргаем UI на каждый чанк.
                    if (publishJob?.isActive != true) {
                        publishJob = launch {
                            delay(80)
                            _calibrationLog.value = synchronized(buf) { buf.toString() }
                        }
                    }
                }
            }
            val cmd = getApplication<Application>().getString(
                com.example.connectapp.R.string.cmd_two
            ) + _settings.value.lineEnding.suffix
            repo.send(cmd).onFailure { e ->
                synchronized(buf) { buf.append("\n[ERROR] ${e.message ?: "send failed"}\n") }
            }
            delay(CALIBRATION_TIMEOUT_MS)
            collector.cancel()
            publishJob?.cancel()
            _calibrationLog.value = synchronized(buf) { buf.toString() }
            _calibrationRunning.value = false
        }
    }

    fun resetCalibrationLog() {
        _calibrationLog.value = ""
    }

    private fun scheduleSave() {
        saveLogJob?.cancel()
        saveLogJob = viewModelScope.launch {
            delay(Constants.LOG_SAVE_DEBOUNCE_MS)
            logBuffer.flush()
            handle[KEY_LOG] = logBuffer.text.value
        }
    }

    /**
     * Накапливает входящие байты в [lineBuffer], извлекает законченные строки,
     * парсит и публикует значения в [SensorDataBus].
     *
     * Работа с [lineBuffer] под synchronized — [clearLog] чистит его с Main,
     * а этот метод бежит на Dispatchers.Default. StringBuilder не thread-safe.
     */
    private fun parseChunk(chunk: String) {
        // Нормализуем переводы строк: некоторые прошивки шлют только '\r'.
        val normalised = chunk.replace("\r\n", "\n").replace('\r', '\n')
        val records = synchronized(lineBuffer) {
            lineBuffer.append(normalised)
            DataParser.drainRecords(lineBuffer)
        }
        for (line in records) {
            val parsed = DataParser.parse(line)
            if (parsed == null) {
                // Не телеметрия — текстовый ответ (help/меню/статус). В отдельный лог.
                CommandLog.appendIfText(line)
                com.example.connectapp.utils.RrdLog.feedLine(line)
                continue
            }
            val merged = _sensorData.updateAndGet { it.merge(parsed) }
            parsed.temperature1?.let { SensorDataBus.addTemperature(slot = 1, value = it) }
            parsed.temperature2?.let { SensorDataBus.addTemperature(slot = 2, value = it) }
            SensorDataBus.publishAccel(parsed, merged)
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveLogJob?.cancel()
        logBuffer.flush()
        handle[KEY_LOG] = logBuffer.text.value
        recorder.shutdown()
        repo.release()
        // VM умирает — сервис тоже стопаем, иначе остаётся "висячий"
        // notification без активного соединения.
        ConnectionForegroundService.stop(getApplication(), FG_OWNER)
        // Снимаем себя из global-status — иначе банер на главной залипает.
        GlobalConnectionStatus.set(FG_OWNER, null)
    }

    companion object {
        private const val KEY_ADDRESS = "bt.address"
        const val KEY_LOG = "bt.log"
        /** Сколько слушать ответ платы после отправки '2'. */
        private const val CALIBRATION_TIMEOUT_MS = 8000L
        private const val FG_OWNER = "bt"
    }
}
