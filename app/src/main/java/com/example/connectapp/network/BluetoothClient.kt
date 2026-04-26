package com.example.connectapp.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Bluetooth SPP client. Connects to a remote device using the standard
 * Serial Port Profile UUID and exposes incoming bytes as a Flow<String>.
 */
class BluetoothClient {

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    /**
     * Connect to a remote device by MAC address.
     * Caller must hold BLUETOOTH_CONNECT (API 31+) before invoking.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(adapter: BluetoothAdapter, address: String) = withContext(Dispatchers.IO) {
        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        // Discovery interferes with connection; cancel before connecting.
        runCatching { adapter.cancelDiscovery() }

        val s = device.createRfcommSocketToServiceRecord(Constants.SPP_UUID)
        try {
            s.connect()
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
        socket = s
        output = s.outputStream
    }

    fun incoming(): Flow<String> = callbackFlow {
        val s = socket ?: throw IllegalStateException("Bluetooth socket is not connected")
        val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
        val buffer = CharArray(Constants.READ_BUFFER_SIZE)
        try {
            while (s.isConnected) {
                val read = reader.read(buffer)
                if (read == -1) break
                if (read > 0) trySend(String(buffer, 0, read))
            }
        } catch (_: Throwable) {
            // Closed below
        } finally {
            close()
        }
        awaitClose { runCatching { reader.close() } }
    }.flowOn(Dispatchers.IO)

    suspend fun send(payload: String) = withContext(Dispatchers.IO) {
        output?.apply {
            write(payload.toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true
}
