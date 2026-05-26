package com.example.connectapp.network

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal class LineDecoder {
    private val acc = ByteArrayOutputStream()

    companion object {
        private const val MAX_LINE_BYTES = 4096
    }

    fun feed(bytes: ByteArray, count: Int): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until count) {
            val b = bytes[i]
            if (b == '\n'.code.toByte() || b == '\r'.code.toByte()) {
                if (acc.size() > 0) {
                    lines += emit(acc.toByteArray(), acc.size())
                    acc.reset()
                }
            } else {
                acc.write(b.toInt())
                if (acc.size() >= MAX_LINE_BYTES) {
                    val buf = acc.toByteArray()
                    val boundary = lastCompleteBoundary(buf)
                    lines += emit(buf, boundary)
                    acc.reset()
                    for (j in boundary until buf.size) acc.write(buf[j].toInt())
                }
            }
        }
        return lines
    }

    fun flush(): String? {
        if (acc.size() == 0) return null
        val s = emit(acc.toByteArray(), acc.size())
        acc.reset()
        return s.takeIf { it.isNotEmpty() }
    }

    private fun emit(buf: ByteArray, length: Int): String =
        String(buf, 0, length, StandardCharsets.UTF_8)

    // Returns index of last complete UTF-8 boundary so that split multi-byte
    // sequences at MAX_LINE_BYTES are not corrupted.
    private fun lastCompleteBoundary(bytes: ByteArray): Int {
        var i = bytes.size - 1
        while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) i--
        if (i < 0) return bytes.size
        val lead = bytes[i].toInt() and 0xFF
        val seqLen = when {
            lead < 0x80 -> 1
            lead < 0xC0 -> 1
            lead < 0xE0 -> 2
            lead < 0xF0 -> 3
            else -> 4
        }
        return if (i + seqLen <= bytes.size) bytes.size else i
    }
}
