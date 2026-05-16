package com.example.connectapp.utils

/**
 * Парсинг и кодирование HEX-строк для бинарной отправки команд.
 *
 *   "AA 55 01 FF" → [0xAA, 0x55, 0x01, 0xFF]
 *   "aa55 01ff"   → [0xAA, 0x55, 0x01, 0xFF]   (пробелы опциональны)
 *   "AA-55-01-FF" → [0xAA, 0x55, 0x01, 0xFF]   (разделители: пробел, дефис, двоеточие)
 *
 * Возвращает null, если в строке мусор или нечётное количество HEX-цифр.
 */
object HexCodec {

    private val cleanRegex = Regex("""[\s\-:]+""")

    fun parse(input: String): ByteArray? {
        val cleaned = input.replace(cleanRegex, "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Обратное: байты → "AA 55 01" с пробелами и заглавными цифрами. */
    fun encode(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
