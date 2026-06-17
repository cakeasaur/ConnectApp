package com.example.connectapp.data.settings

/** Какой суффикс добавлять к каждой отправляемой команде. */
enum class LineEnding(val suffix: String) {
    LF("\n"),       // Linux/Arduino
    CR("\r"),       // классические PIC/Microchip-прошивки
    CRLF("\r\n"),   // Windows-style, многие SCPI устройства
    NONE("")        // если уже терминирует юзер
}

/**
 * Вид отрисовки линейного графика. Выбирается дропдауном «Вид графика» на
 * каждой 2D-карточке (Темп/Аксел-1/Аксел-2/динамические) независимо.
 *   LINE   — ломаная по точкам + glow (видно реальную форму/дрожь).
 *   SMOOTH — сглаженная bezier-линия без glow (чисто, для отчёта/скриншота).
 *   AREA   — сглаженная линия + градиентная заливка под ней.
 *   BARS   — вертикальные столбики от нуля (дискретные отсчёты).
 *   DOTS   — точки-сэмплы без линии (видно частоту дискретизации/пропуски).
 */
enum class ChartStyle(val label: String) {
    LINE("Линейный"), SMOOTH("Сглаженный"), AREA("Площадной"), BARS("Столбчатый"), DOTS("Точки")
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
    val lineEnding: LineEnding = LineEnding.CRLF,
    /** Если включено — поле «Сообщение» парсит ввод как HEX-байты ("AA 55 01"). */
    val hexSendMode: Boolean = false,
    /** Набор кастомных чипов-команд над полем ввода. Редактируется в Настройках. */
    val quickCommands: List<QuickCommand> = QuickCommand.DEFAULT,
    /**
     * Частота опроса платы в Гц. Используется для FFT/STFT/Cross-correlation:
     * частоты на оси спектра, лаги в секундах, period detection. Если поменять —
     * подписи на спектре будут соответствовать реальной частоте дискретизации.
     * Стандартные значения: 1/5/10/20/50 Гц. По умолчанию 10 Гц (PIC24 default).
     */
    val sampleRateHz: Float = 10f,
    /**
     * Чувствительность акселерометра в LSB на 1g — делитель для перевода сырых
     * LSB в физические g на графиках (режим «g»). Дефолт 1000: на этой плате
     * ось az покоится ~1000 LSB при 1g (гравитация). Настраивается под датчик.
     */
    val accelSensitivityLsbPerG: Float = 1000f,
    /** Сохранённые профили плат (именованные наборы настроек). */
    val boardProfiles: List<BoardProfile> = emptyList(),
    /** Имя активного профиля, либо null если ни один не выбран. */
    val activeProfile: String? = null,
    /** Пройден ли стартовый онбординг-визард. Пока false — показываем его. */
    val onboardingDone: Boolean = false,
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
