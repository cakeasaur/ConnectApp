package com.example.connectapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Тонкая обёртка над DataStore — Flow + два setter'а. */
class SettingsRepository(private val context: Context) {

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
            hexSendMode = prefs[KEY_HEX_SEND] ?: AppSettings.DEFAULT.hexSendMode
        )
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

    companion object {
        private const val MAX_HISTORY = 5
        private val KEY_AUTO_RECONNECT: Preferences.Key<Boolean> = booleanPreferencesKey("auto_reconnect")
        private val KEY_AUTO_MONITOR: Preferences.Key<Boolean> = booleanPreferencesKey("auto_monitor")
        private val KEY_AUTO_SCROLL: Preferences.Key<Boolean> = booleanPreferencesKey("auto_scroll")
        private val KEY_DARK_THEME: Preferences.Key<Boolean> = booleanPreferencesKey("dark_theme")
        private val KEY_LINE_ENDING: Preferences.Key<String> = stringPreferencesKey("line_ending")
        private val KEY_HISTORY: Preferences.Key<String> = stringPreferencesKey("conn_history")
        private val KEY_HEX_SEND: Preferences.Key<Boolean> = booleanPreferencesKey("hex_send")
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
