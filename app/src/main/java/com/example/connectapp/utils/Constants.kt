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

    /** Permission request codes. */
    const val REQ_BLUETOOTH_PERMS = 1001
    const val REQ_WIFI_PERMS = 1002
}
