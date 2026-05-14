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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Bluetooth SPP client. Connects to a remote device using the standard
 * Serial Port Profile UUID and exposes incoming bytes as a Flow<String>.
 */
class BluetoothClient {

    // @Volatile: socket/output читаются из incoming/send/close, которые могут
    // выполняться на разных потоках Dispatchers.IO. Без volatile — гонка чтения
    // stale-ссылки на уже закрытый сокет.
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    // Serialises concurrent send() calls — without this, bytes from
    // overlapping commands ("temp" + "status") can interleave on the wire.
    private val sendMutex = Mutex()

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

    /**
     * Read raw bytes from the input stream and emit as UTF-8 strings.
     *
     * Uses raw [InputStream] (not BufferedReader/InputStreamReader) — the
     * Reader chain occasionally inserts/drops characters at chunk boundaries,
     * producing garbled text like "ahecelerometer" instead of "accelerometer".
     */
    fun incoming(): Flow<String> = callbackFlow {
        val s = socket ?: throw IllegalStateException("Bluetooth socket is not connected")
        val input: InputStream = s.inputStream
        val buffer = ByteArray(Constants.READ_BUFFER_SIZE)
        try {
            while (s.isConnected) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) trySend(String(buffer, 0, read, StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
            // Closed below
        } finally {
            close()
        }
        awaitClose { runCatching { input.close() } }
    }.flowOn(Dispatchers.IO)

    /**
     * Отправляет [payload] как есть. Терминатор (если нужен) добавляет вызывающий
     * слой — у разных устройств он разный (\n / \r / \r\n / отсутствует).
     */
    suspend fun send(payload: String) = withContext(Dispatchers.IO) {
        // Mutex prevents concurrent writes from interleaving at the byte level.
        sendMutex.withLock {
            val out = output ?: throw IllegalStateException("Bluetooth socket is not connected")
            out.write(payload.toByteArray(StandardCharsets.UTF_8))
            out.flush()
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
