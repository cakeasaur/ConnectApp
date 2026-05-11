package com.example.connectapp.utils

import java.util.UUID

object Constants {

    /** Standard Serial Port Profile UUID for Bluetooth SPP. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Socket connect/read timeout, milliseconds. */
    const val SOCKET_TIMEOUT_MS = 30_000

    /** Buffer size used by readers. */
    const val READ_BUFFER_SIZE = 1024

    /** Bluetooth discovery duration cap, milliseconds. */
    const val DISCOVERY_TIMEOUT_MS = 12_000L

    /** Delay before auto-reconnect attempt, milliseconds. */
    const val RECONNECT_DELAY_MS = 3_000L

    /** Maximum number of data points kept in graph history. */
    const val GRAPH_MAX_POINTS = 60

    /** Maximum characters kept in the log. Oldest lines are dropped beyond this. */
    const val MAX_LOG_CHARS = 30_000

    /** Delay before persisting log to SavedStateHandle (debounce), ms. */
    const val LOG_SAVE_DEBOUNCE_MS = 2_000L

    /** Minimum interval between chart refreshes, ms. */
    const val CHART_THROTTLE_MS = 300L

    /** Permission request codes. */
    const val REQ_BLUETOOTH_PERMS = 1001
    const val REQ_WIFI_PERMS = 1002
}
