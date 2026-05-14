package com.example.connectapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
            darkTheme = if (prefs.contains(KEY_DARK_THEME)) prefs[KEY_DARK_THEME] else AppSettings.DEFAULT.darkTheme
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

    companion object {
        private val KEY_AUTO_RECONNECT: Preferences.Key<Boolean> = booleanPreferencesKey("auto_reconnect")
        private val KEY_AUTO_MONITOR: Preferences.Key<Boolean> = booleanPreferencesKey("auto_monitor")
        private val KEY_AUTO_SCROLL: Preferences.Key<Boolean> = booleanPreferencesKey("auto_scroll")
        private val KEY_DARK_THEME: Preferences.Key<Boolean> = booleanPreferencesKey("dark_theme")
    }
}
