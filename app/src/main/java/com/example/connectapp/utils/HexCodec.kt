package com.example.connectapp.utils

/**
 * Парсинг и кодирование HEX-строк для бинарной отправки команд.
 *
 *   "AA 55 01 FF" → [0xAA, 0x55, 0x01, 0xFF]
 *   "aa55 01ff"   → [0xAA, 0x55, 0x01, 0xFF]   (пробелы опциональны)
 *   "AA-55-01-FF" → [0xAA, 0x55, 0x01, 0xFF]   (разделители: пробел, дефис, двоеточие)
 */
object HexCodec {

    private val cleanRegex = Regex("""[\s\-:]+""")

    /** Подробный результат парсинга — позволяет UI показать осмысленное сообщение. */
    sealed class Result {
        data class Ok(val bytes: ByteArray) : Result()
        object Empty : Result()
        object OddLength : Result()
        data class InvalidChar(val char: Char) : Result()
    }

    fun parseDetailed(input: String): Result {
        val cleaned = input.replace(cleanRegex, "")
        if (cleaned.isEmpty()) return Result.Empty
        if (cleaned.length % 2 != 0) return Result.OddLength
        val bad = cleaned.firstOrNull { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
        if (bad != null) return Result.InvalidChar(bad)
        val bytes = ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Result.Ok(bytes)
    }

    /** Compatibility-обёртка: возвращает null если парсинг не удался. */
    fun parse(input: String): ByteArray? =
        (parseDetailed(input) as? Result.Ok)?.bytes

    /** Человеко-читаемое описание ошибки для UI (или null если успех). */
    fun describeError(input: String): String? = when (val r = parseDetailed(input)) {
        is Result.Ok -> null
        Result.Empty -> "пустая строка"
        Result.OddLength -> "нечётное число hex-цифр"
        is Result.InvalidChar -> "недопустимый символ '${r.char}'"
    }

    /** Обратное: байты → "AA 55 01" с пробелами и заглавными цифрами. */
    fun encode(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
