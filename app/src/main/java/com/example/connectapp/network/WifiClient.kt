package com.example.connectapp.network

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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Thin TCP client. Owns a single Socket and exposes incoming bytes
 * as a Flow<String>. Operations are blocking I/O — always invoked
 * via Dispatchers.IO from the repository layer.
 */
class WifiClient {

    private var socket: Socket? = null
    private var output: OutputStream? = null

    /** Connect to host:port. Throws on failure. */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), Constants.SOCKET_TIMEOUT_MS)
        socket = s
        output = s.getOutputStream()
    }

    /**
     * Continuous read stream. Emits decoded UTF-8 chunks.
     * Cold flow — closes the socket when collection is cancelled.
     */
    fun incoming(): Flow<String> = callbackFlow {
        val s = socket ?: throw IllegalStateException("Socket is not connected")
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        val buffer = CharArray(Constants.READ_BUFFER_SIZE)
        try {
            while (!s.isClosed) {
                val read = reader.read(buffer)
                if (read == -1) break
                if (read > 0) trySend(String(buffer, 0, read))
            }
        } catch (_: Throwable) {
            // Channel close path below propagates
        } finally {
            close()
        }
        awaitClose { runCatching { reader.close() } }
    }.flowOn(Dispatchers.IO)

    suspend fun send(payload: String) = withContext(Dispatchers.IO) {
        output?.apply {
            // Append newline if missing — most devices (HC-05, ESP32, Arduino)
            // expect line-delimited messages.
            val msg = if (payload.endsWith("\n")) payload else "$payload\n"
            write(msg.toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false
}
