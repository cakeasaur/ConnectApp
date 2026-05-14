package com.example.connectapp.data.settings

/** Настройки приложения, хранятся в DataStore Preferences. */
data class AppSettings(
    /** При запуске сразу подключаться к последнему успешному MAC. */
    val autoReconnect: Boolean = true,
    /** Сразу после Connected отправить команду `monitor`. */
    val autoMonitor: Boolean = false,
    /** Автоскролл лога при появлении новых строк. */
    val autoScrollLog: Boolean = true,
    /** Принудительная тёмная тема (true) / системная (null) / светлая (false). */
    val darkTheme: Boolean? = true
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
