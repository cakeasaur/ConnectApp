package com.example.connectapp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.connectapp.data.mqtt.MqttConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Тонкая обёртка над DataStore — Flow + два setter'а. */
class SettingsRepository(private val context: Context) {

    /**
     * EncryptedSharedPreferences для секретов (MQTT-пароля).
     * Мастер-ключ генерируется в Android Keystore (AES256-GCM), сами значения
     * шифруются AES256-GCM. ESP — единственный простой способ положить
     * чувствительное поле так, чтобы оно не утекло в backup/ADB pull.
     *
     * Lazy — инициализация ESP делает первый IO + Keystore-операцию, не хочется
     * платить за это, если юзер MQTT не трогает.
     */
    private val secretsPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mqtt_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: AppSettings.DEFAULT.autoReconnect,
            autoMonitor = prefs[KEY_AUTO_MONITOR] ?: AppSettings.DEFAULT.autoMonitor,
            autoScrollLog = prefs[KEY_AUTO_SCROLL] ?: AppSettings.DEFAULT.autoScrollLog,
            // null означает «следовать системе».
            darkTheme = if (prefs.contains(KEY_DARK_THEME)) prefs[KEY_DARK_THEME] else AppSettings.DEFAULT.darkTheme,
            lineEnding = prefs[KEY_LINE_ENDING]?.let { name ->
                runCatching { LineEnding.valueOf(name) }.getOrDefault(AppSettings.DEFAULT.lineEnding)
            } ?: AppSettings.DEFAULT.lineEnding,
            hexSendMode = prefs[KEY_HEX_SEND] ?: AppSettings.DEFAULT.hexSendMode,
            quickCommands = QuickCommand.decodeList(prefs[KEY_QUICK_CMDS]),
            sampleRateHz = prefs[KEY_SAMPLE_RATE_HZ] ?: AppSettings.DEFAULT.sampleRateHz,
            accelSensitivityLsbPerG = prefs[KEY_ACCEL_SENS] ?: AppSettings.DEFAULT.accelSensitivityLsbPerG,
            boardProfiles = BoardProfile.decodeList(prefs[KEY_PROFILES]),
            activeProfile = prefs[KEY_ACTIVE_PROFILE],
        )
    }

    suspend fun setSampleRateHz(value: Float) =
        context.settingsDataStore.edit { it[KEY_SAMPLE_RATE_HZ] = value }

    suspend fun setAccelSensitivity(value: Float) =
        context.settingsDataStore.edit { it[KEY_ACCEL_SENS] = value }

    /** Сохраняет/перезаписывает профиль по имени и делает его активным. */
    suspend fun saveProfile(p: BoardProfile) = context.settingsDataStore.edit { prefs ->
        val next = BoardProfile.decodeList(prefs[KEY_PROFILES]).filter { it.name != p.name } + p
        prefs[KEY_PROFILES] = BoardProfile.encodeList(next)
        prefs[KEY_ACTIVE_PROFILE] = p.name
    }

    /** Удаляет профиль по имени; снимает активность, если удаляли активный. */
    suspend fun deleteProfile(name: String) = context.settingsDataStore.edit { prefs ->
        val next = BoardProfile.decodeList(prefs[KEY_PROFILES]).filter { it.name != name }
        prefs[KEY_PROFILES] = BoardProfile.encodeList(next)
        if (prefs[KEY_ACTIVE_PROFILE] == name) prefs.remove(KEY_ACTIVE_PROFILE)
    }

    /** Применяет профиль: записывает его параметры в активные настройки. */
    suspend fun applyProfile(p: BoardProfile) = context.settingsDataStore.edit { prefs ->
        prefs[KEY_LINE_ENDING] = p.lineEnding.name
        prefs[KEY_SAMPLE_RATE_HZ] = p.sampleRateHz
        prefs[KEY_ACCEL_SENS] = p.accelSensitivityLsbPerG
        prefs[KEY_QUICK_CMDS] = QuickCommand.encodeList(p.quickCommands)
        prefs[KEY_ACTIVE_PROFILE] = p.name
    }

    suspend fun setAutoReconnect(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_AUTO_RECONNECT] = value }
    suspend fun setAutoMonitor(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_AUTO_MONITOR] = value }
    suspend fun setAutoScrollLog(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_AUTO_SCROLL] = value }
    suspend fun setDarkTheme(value: Boolean?) = context.settingsDataStore.edit {
        if (value == null) it.remove(KEY_DARK_THEME) else it[KEY_DARK_THEME] = value
    }
    suspend fun setLineEnding(value: LineEnding) =
        context.settingsDataStore.edit { it[KEY_LINE_ENDING] = value.name }
    suspend fun setHexSendMode(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_HEX_SEND] = value }
    suspend fun setQuickCommands(list: List<QuickCommand>) =
        context.settingsDataStore.edit { it[KEY_QUICK_CMDS] = QuickCommand.encodeList(list) }

    /** Поток истории подключений: `"name|MAC"`, разделители `;`. */
    val connectionHistory: Flow<List<ConnectionHistoryEntry>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_HISTORY].orEmpty()
                .split(';')
                .mapNotNull { ConnectionHistoryEntry.parse(it) }
        }

    /** Поднимает [entry] в начало истории, ограничивает 5 элементами. */
    suspend fun pushConnection(entry: ConnectionHistoryEntry) {
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[KEY_HISTORY].orEmpty()
                .split(';')
                .mapNotNull { ConnectionHistoryEntry.parse(it) }
                .filter { it.address != entry.address }
            val updated = (listOf(entry) + existing).take(MAX_HISTORY)
            prefs[KEY_HISTORY] = updated.joinToString(";") { it.encode() }
        }
    }

    suspend fun clearHistory() = context.settingsDataStore.edit { it.remove(KEY_HISTORY) }

    // ============================================================
    // MQTT bridge config
    // ============================================================

    val mqttConfig: Flow<MqttConfig> = context.settingsDataStore.data.map { prefs ->
        MqttConfig(
            enabled = prefs[MQ_ENABLED] ?: MqttConfig.DEFAULT.enabled,
            host = prefs[MQ_HOST] ?: MqttConfig.DEFAULT.host,
            port = prefs[MQ_PORT] ?: MqttConfig.DEFAULT.port,
            useTls = prefs[MQ_TLS] ?: MqttConfig.DEFAULT.useTls,
            trustAllCerts = prefs[MQ_TRUST_ALL] ?: MqttConfig.DEFAULT.trustAllCerts,
            username = prefs[MQ_USER] ?: MqttConfig.DEFAULT.username,
            // Пароль читается из EncryptedSharedPreferences, а не DataStore.
            password = secretsPrefs.getString(SECRET_PASS, MqttConfig.DEFAULT.password)
                ?: MqttConfig.DEFAULT.password,
            clientId = prefs[MQ_CLIENT_ID] ?: MqttConfig.DEFAULT.clientId,
            topicPrefix = prefs[MQ_TOPIC_PREFIX] ?: MqttConfig.DEFAULT.topicPrefix,
            publishQos = (prefs[MQ_QOS] ?: MqttConfig.DEFAULT.publishQos).coerceIn(0, 2),
            publishRetained = prefs[MQ_RETAINED] ?: MqttConfig.DEFAULT.publishRetained,
            throttleMs = (prefs[MQ_THROTTLE] ?: MqttConfig.DEFAULT.throttleMs).coerceAtLeast(0),
            subscribeCommands = prefs[MQ_SUB_CMD] ?: MqttConfig.DEFAULT.subscribeCommands,
        )
    }

    suspend fun setMqttConfig(cfg: MqttConfig) {
        // Пароль — в ESP (синхронный API). Остальное — в DataStore.
        secretsPrefs.edit().putString(SECRET_PASS, cfg.password).apply()
        context.settingsDataStore.edit { p ->
            p[MQ_ENABLED] = cfg.enabled
            p[MQ_HOST] = cfg.host
            p[MQ_PORT] = cfg.port
            p[MQ_TLS] = cfg.useTls
            p[MQ_TRUST_ALL] = cfg.trustAllCerts
            p[MQ_USER] = cfg.username
            p[MQ_CLIENT_ID] = cfg.clientId
            p[MQ_TOPIC_PREFIX] = cfg.topicPrefix
            p[MQ_QOS] = cfg.publishQos
            p[MQ_RETAINED] = cfg.publishRetained
            p[MQ_THROTTLE] = cfg.throttleMs
            p[MQ_SUB_CMD] = cfg.subscribeCommands
            // Bump version: гарантируем DataStore-emit даже когда поменялся
            // только пароль (он в ESP, не в DataStore, DataStore сам по себе
            // изменений не заметил бы → flow не переиздался бы → bridge не
            // переподключился бы с новым паролем).
            p[MQ_VERSION] = (p[MQ_VERSION] ?: 0) + 1
            // Старый ключ пароля из ранней версии: чистим, чтобы plain-text
            // не лежал в DataStore у тех, кто успел сохранить его до этого фикса.
            p.remove(MQ_PASS_LEGACY)
        }
    }

    /**
     * Гарантирует, что у MQTT-моста есть уникальный topic-prefix. Первый
     * запуск — генерируем `connectapp-<8hex>`, чтобы случайно не пересечься
     * с другим юзером на публичном брокере. Идемпотентно: повторные вызовы
     * не перетирают существующий префикс.
     */
    suspend fun ensureMqttDefaults() {
        val current = context.settingsDataStore.data.first()[MQ_TOPIC_PREFIX]
        if (current.isNullOrBlank()) {
            val random = "connectapp-${UUID.randomUUID().toString().take(8)}"
            context.settingsDataStore.edit { it[MQ_TOPIC_PREFIX] = random }
        }
    }

    companion object {
        private const val MAX_HISTORY = 5
        private val KEY_AUTO_RECONNECT: Preferences.Key<Boolean> = booleanPreferencesKey("auto_reconnect")
        private val KEY_AUTO_MONITOR: Preferences.Key<Boolean> = booleanPreferencesKey("auto_monitor")
        private val KEY_AUTO_SCROLL: Preferences.Key<Boolean> = booleanPreferencesKey("auto_scroll")
        private val KEY_DARK_THEME: Preferences.Key<Boolean> = booleanPreferencesKey("dark_theme")
        private val KEY_LINE_ENDING: Preferences.Key<String> = stringPreferencesKey("line_ending")
        private val KEY_HISTORY: Preferences.Key<String> = stringPreferencesKey("conn_history")
        private val KEY_HEX_SEND: Preferences.Key<Boolean> = booleanPreferencesKey("hex_send")
        private val KEY_QUICK_CMDS: Preferences.Key<String> = stringPreferencesKey("quick_commands")
        private val KEY_SAMPLE_RATE_HZ: Preferences.Key<Float> = floatPreferencesKey("sample_rate_hz")
        private val KEY_ACCEL_SENS: Preferences.Key<Float> = floatPreferencesKey("accel_sens_lsb_per_g")
        private val KEY_PROFILES: Preferences.Key<String> = stringPreferencesKey("board_profiles")
        private val KEY_ACTIVE_PROFILE: Preferences.Key<String> = stringPreferencesKey("active_profile")

        // MQTT — отдельный неймспейс ключей (префикс mqtt_).
        // Пароль хранится plain в DataStore: для лабораторного использования
        // приемлемо; если нужна enterprise-secure-storage — EncryptedSharedPreferences.
        private val MQ_ENABLED = booleanPreferencesKey("mqtt_enabled")
        private val MQ_HOST = stringPreferencesKey("mqtt_host")
        private val MQ_PORT = intPreferencesKey("mqtt_port")
        private val MQ_TLS = booleanPreferencesKey("mqtt_tls")
        private val MQ_TRUST_ALL = booleanPreferencesKey("mqtt_trust_all")
        private val MQ_USER = stringPreferencesKey("mqtt_user")
        // Старый ключ plain-пароля. Не используется на чтение; зануляем на
        // setMqttConfig чтобы убрать утечку при апгрейде с прошлой версии.
        private val MQ_PASS_LEGACY = stringPreferencesKey("mqtt_pass")
        private val MQ_CLIENT_ID = stringPreferencesKey("mqtt_client_id")
        private val MQ_TOPIC_PREFIX = stringPreferencesKey("mqtt_topic_prefix")
        private val MQ_QOS = intPreferencesKey("mqtt_qos")
        private val MQ_RETAINED = booleanPreferencesKey("mqtt_retained")
        private val MQ_THROTTLE = intPreferencesKey("mqtt_throttle_ms")
        private val MQ_SUB_CMD = booleanPreferencesKey("mqtt_sub_cmd")
        // Bump-counter — гарантия emit'а DataStore-flow при изменении только
        // ESP-полей (password). Не отображается в UI.
        private val MQ_VERSION = intPreferencesKey("mqtt_version")

        // EncryptedSharedPreferences key для пароля.
        private const val SECRET_PASS = "mqtt_password"
    }
}

/**
 * Запись из истории подключений. Сериализуется как `"name|MAC"`,
 * несколько записей разделяются `;` — не лезем в JSON ради 2 полей.
 */
data class ConnectionHistoryEntry(val name: String, val address: String) {
    fun encode() = "${name.replace("|", "_").replace(";", "_")}|$address"
    companion object {
        fun parse(s: String): ConnectionHistoryEntry? {
            val parts = s.split('|', limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) return null
            return ConnectionHistoryEntry(parts[0], parts[1])
        }
    }
}
