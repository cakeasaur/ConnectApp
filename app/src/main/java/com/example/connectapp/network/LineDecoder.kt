package com.example.connectapp.network

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal class LineDecoder {
    private val acc = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, count: Int): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until count) {
            val b = bytes[i]
            if (b == '\n'.code.toByte()) {
                val s = acc.toString(StandardCharsets.UTF_8.name())
                lines += if (s.endsWith('\r')) s.dropLast(1) else s
                acc.reset()
            } else {
                acc.write(b.toInt())
            }
        }
        return lines
    }

    fun flush(): String? {
        if (acc.size() == 0) return null
        val s = acc.toString(StandardCharsets.UTF_8.name())
        acc.reset()
        return s
    }
}
