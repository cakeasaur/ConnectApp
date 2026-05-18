package com.example.connectapp.utils

import java.util.UUID

object Constants {

    /** Standard Serial Port Profile UUID for Bluetooth SPP. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * TCP connect-timeout (socket.connect()), milliseconds.
     *
     * 10с — компромисс: Wi-Fi-плата в полевых условиях иногда отвечает на
     * SYN с заметной задержкой (boot ESP/PIC, переключение AP). Меньшее
     * значение даёт ложные "connect failed", большее — заставляет юзера
     * слишком долго смотреть на спиннер при реально дохлом адресе.
     */
    const val SOCKET_CONNECT_TIMEOUT_MS = 10_000

    /**
     * TCP read-timeout (SO_TIMEOUT для read()), milliseconds. 0 = бесконечный.
     *
     * Раньше read() и connect() делили один таймаут 30 сек. На тихом, но живом
     * соединении (плата ничего не шлёт пока юзер не нажмёт monitor) read()
     * через 30с кидал SocketTimeoutException → UI ошибочно показывал «связь
     * потеряна». Ставим 0 — read() ждёт сколько надо, разрыв детектится по
     * EOF/IOException от реального разрыва TCP.
     */
    const val SOCKET_READ_TIMEOUT_MS = 0

    /** Buffer size used by readers. */
    const val READ_BUFFER_SIZE = 1024

    /**
     * Bluetooth discovery duration cap, milliseconds.
     *
     * Системный startDiscovery() сам останавливается ~через 12с — мы
     * подстраховываемся таким же значением, чтобы UI-флаг `_scanning`
     * не залип, если broadcast ACTION_DISCOVERY_FINISHED по какой-то
     * причине не пришёл (бывало на некоторых вендорных прошивках).
     */
    const val DISCOVERY_TIMEOUT_MS = 12_000L

    /** Delay before auto-reconnect attempt, milliseconds. */
    const val RECONNECT_DELAY_MS = 3_000L

    /** Maximum characters kept in the log. Oldest lines are dropped beyond this. */
    const val MAX_LOG_CHARS = 30_000

    /** Delay before persisting log to SavedStateHandle (debounce), ms. */
    const val LOG_SAVE_DEBOUNCE_MS = 2_000L
}
