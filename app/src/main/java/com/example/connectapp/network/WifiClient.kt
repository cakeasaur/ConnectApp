package com.example.connectapp.network

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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Thin TCP client. Owns a single Socket and exposes incoming bytes
 * as a Flow<String>. Operations are blocking I/O — always invoked
 * via Dispatchers.IO from the repository layer.
 */
class WifiClient {

    // @Volatile: эти поля читаются/пишутся из разных корутин (connect/send/close/incoming),
    // которые могут попадать в разные потоки Dispatchers.IO.
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    // Serialises concurrent send() calls — without this, bytes from
    // overlapping commands can interleave on the wire.
    private val sendMutex = Mutex()

    /** Connect to host:port. Throws on failure. */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), Constants.SOCKET_TIMEOUT_MS)
        // SO_TIMEOUT: без этого read() висит бесконечно при «чёрной дыре» (NAT-таймаут,
        // отключённый Wi-Fi без RST). С таймаутом получим SocketTimeoutException
        // → репозиторий пометит соединение как разорванное и (для BT) попробует переподключиться.
        s.soTimeout = Constants.SOCKET_TIMEOUT_MS
        s.keepAlive = true
        socket = s
        output = s.getOutputStream()
    }

    /**
     * Continuous read stream. Emits decoded UTF-8 chunks.
     *
     * Uses raw [InputStream] (not BufferedReader/InputStreamReader) — the
     * Reader chain can produce garbled text at chunk boundaries.
     */
    fun incoming(): Flow<String> = callbackFlow {
        val s = socket ?: throw IllegalStateException("Socket is not connected")
        val input: InputStream = s.getInputStream()
        val buffer = ByteArray(Constants.READ_BUFFER_SIZE)
        try {
            while (!s.isClosed) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) trySend(String(buffer, 0, read, StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
            // Любая I/O-ошибка (включая SocketTimeoutException) — для нас разрыв.
        } finally {
            close()
        }
        awaitClose { runCatching { input.close() } }
    }.flowOn(Dispatchers.IO)

    suspend fun send(payload: String) = withContext(Dispatchers.IO) {
        // Mutex prevents concurrent writes from interleaving at the byte level.
        sendMutex.withLock {
            val out = output ?: throw IllegalStateException("Socket is not connected")
            // Append newline if missing — most devices (HC-05, ESP32, Arduino)
            // expect line-delimited messages.
            val msg = if (payload.endsWith("\n")) payload else "$payload\n"
            out.write(msg.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    fun isConnected(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true
}
