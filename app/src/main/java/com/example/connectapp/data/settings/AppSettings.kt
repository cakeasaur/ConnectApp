package com.example.connectapp.data.settings

/** Какой суффикс добавлять к каждой отправляемой команде. */
enum class LineEnding(val suffix: String) {
    LF("\n"),       // Linux/Arduino
    CR("\r"),       // классические PIC/Microchip-прошивки
    CRLF("\r\n"),   // Windows-style, многие SCPI устройства
    NONE("")        // если уже терминирует юзер
}

/** Настройки приложения, хранятся в DataStore Preferences. */
data class AppSettings(
    /** При запуске сразу подключаться к последнему успешному MAC. */
    val autoReconnect: Boolean = true,
    /** Сразу после Connected отправить команду `monitor`. */
    val autoMonitor: Boolean = false,
    /** Автоскролл лога при появлении новых строк. */
    val autoScrollLog: Boolean = true,
    /** Принудительная тёмная тема (true) / системная (null) / светлая (false). */
    val darkTheme: Boolean? = true,
    /** Терминатор для исходящих команд. CRLF = безопасный дефолт для большинства MCU. */
    val lineEnding: LineEnding = LineEnding.CRLF
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
