package com.example.connectapp.utils

import com.example.connectapp.data.models.SensorData

/**
 * Универсальный парсер строки телеметрии в [SensorData] — рассчитан на любые
 * платы, а не только на конкретную прошивку.
 *
 * Стратегия (по приоритету):
 *   1. Явные метки — "TEMP: 23.5", "T=28.5", "X: .. Y: .. Z: ..".
 *      Самые однозначные, не зависят от порядка чисел.
 *   2. Структурный CSV — поля через ';', тройки акселерометра сгруппированы
 *      запятыми: "counter;t1;t2;ax,ay,az;ax,ay,az;...". Значения могут быть
 *      целыми ИЛИ дробными; ведущий counter и хвост (CRC/timestamp) игнорируются.
 *   3. Плоский список чисел — "t1 t2 ax ay az ax ay az" с любым разделителем
 *      (',', ';', пробел, таб). Раскладка определяется по количеству чисел.
 *
 * Возвращает null, если распознаваемых значений нет.
 */
object DataParser {

    // Verbose: "Temperature on I2C1: 28.5 C". Decimal обязателен — иначе regex
    // поймает '1' из "I2C1" как температуру.
    private val tempVerboseRegex = Regex("""(?i)temp(?:erature)?\b.*?(-?\d+\.\d+)""")
    // Compact: "T:28.5", "T=28.5". Привязан к началу строки или разделителю.
    private val tempCompactRegex = Regex("""(?i)(?:^|[\s,;>|])t[:\s=]+(-?\d+(?:\.\d+)?)""")
    // Labeled axes: "X: 254", "AX: -5", "Y:..", "Z:..".
    private val axisX = Regex("""(?i)(?:^|[\s,;>|])a?x[:\s=]+(-?\d+(?:\.\d+)?)""")
    private val axisY = Regex("""(?i)(?:^|[\s,;>|])a?y[:\s=]+(-?\d+(?:\.\d+)?)""")
    private val axisZ = Regex("""(?i)(?:^|[\s,;>|])a?z[:\s=]+(-?\d+(?:\.\d+)?)""")
    // Любое число со знаком и опциональной дробной частью (для плоского режима).
    private val numberRegex = Regex("""[-+]?\d*\.?\d+""")
    // Поле-счётчик: чисто целое (без точки и запятых).
    private val pureIntRegex = Regex("""^[+-]?\d+$""")

    // Защита от неограниченного роста lineBuffer, если поток не кадрируется
    // (например, длинный текст команд без '\n'/';').
    private const val MAX_BUFFER = 16_384

    /**
     * Выгребает из буфера [sb] завершённые записи, оставляя незавершённый хвост.
     * Удаляет выданное из [sb].
     *
     * Поддерживает два кадрирования:
     *   1. По '\n' — для прошивок с переводом строки.
     *   2. По счётчику — прошивки шлют записи `counter;t1;t2;ax,ay,az;...;`
     *      БЕЗ '\n', разделяя только ';'. Граница новой записи — чисто целое
     *      поле (счётчик), встреченное после того как в текущей записи уже
     *      была тройка акселерометра (через запятую). Это отличает счётчик от
     *      целых температур, идущих до акселерометра. Последняя (возможно
     *      неполная) запись остаётся в буфере до прихода следующего счётчика.
     */
    fun drainRecords(sb: StringBuilder): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val nl = sb.indexOf("\n")
            if (nl < 0) break
            val line = sb.substring(0, nl).trim()
            sb.delete(0, nl + 1)
            if (line.isNotEmpty()) out.add(line)
        }
        if (sb.indexOf(";") < 0) {
            // Нет ни '\n', ни ';' — это текст команд (help/dump), который не
            // кадрируется. RRD-дамп ловит RrdLog из сырья отдельно, так что
            // здесь можно не копить: режем, оставляя небольшой хвост на случай
            // незавершённой строки.
            if (sb.length > MAX_BUFFER) sb.delete(0, sb.length - 256)
            return out
        }

        val s = sb.toString()
        var recordStart = -1
        var sawTriple = false
        var fieldStart = 0
        while (fieldStart <= s.length) {
            val sep = s.indexOf(';', fieldStart)
            val end = if (sep < 0) s.length else sep
            val tok = s.substring(fieldStart, end).trim()
            if (tok.isNotEmpty()) {
                val isInt = pureIntRegex.matches(tok)
                val isTriple = tok.indexOf(',') >= 0
                if (recordStart < 0) {
                    if (isInt) { recordStart = fieldStart; sawTriple = false }
                } else if (isInt && sawTriple) {
                    out.add(s.substring(recordStart, fieldStart).trim().trimEnd(';'))
                    recordStart = fieldStart
                    sawTriple = false
                }
                if (recordStart >= 0 && isTriple) sawTriple = true
            }
            if (sep < 0) break
            fieldStart = sep + 1
        }
        when {
            recordStart > 0 -> sb.delete(0, recordStart)
            // recordStart <= 0: полного кадра нет (нет счётчика, либо единственная
            // незавершённая запись с позиции 0). Если буфер раздулся — сбрасываем,
            // чтобы не утекать памятью.
            sb.length > MAX_BUFFER -> sb.setLength(0)
        }
        return out
    }

    fun parse(line: String): SensorData? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        val isCalib = raw.contains("calib", ignoreCase = true)

        // 1. Явные метки.
        parseLabeled(raw, blockTemp = isCalib)?.let { return it }
        if (isCalib) return null

        // 2. Структурный CSV с тройками акселерометра.
        parseStructured(raw)?.let { return it }

        // 3. Плоский список чисел. Только если строка состоит из чисел и
        //    разделителей (без букв и без ':') — иначе это текст/лог/время.
        if (raw.none { it.isLetter() } && ':' !in raw) {
            parseFlat(raw)?.let { return it }
        }
        return null
    }

    private fun parseLabeled(line: String, blockTemp: Boolean): SensorData? {
        val temp = if (blockTemp) null else
            (tempVerboseRegex.find(line) ?: tempCompactRegex.find(line))
                ?.groupValues?.get(1)?.toFloatOrNull()
        val x = axisX.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val y = axisY.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val z = axisZ.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        if (temp == null && x == null && y == null && z == null) return null
        return SensorData(temperature1 = temp, accel1X = x, accel1Y = y, accel1Z = z)
    }

    /**
     * Поля через ';'. Тройка чисел через запятую → акселерометр; одиночное
     * число до первой тройки → кандидат в counter/температуру. Хвост после
     * акселерометров (CRC/timestamp) и поля с мусором игнорируются.
     */
    private fun parseStructured(line: String): SensorData? {
        val triples = ArrayList<FloatArray>(2)
        val scalarsBefore = ArrayList<Float>(3)
        var sawTriple = false

        for (fieldRaw in line.split(';')) {
            val field = fieldRaw.trim()
            if (field.isEmpty()) continue
            val parts = field.split(',')
            if (parts.size == 3) {
                val a = parts[0].trim().toFloatOrNull()
                val b = parts[1].trim().toFloatOrNull()
                val c = parts[2].trim().toFloatOrNull()
                if (a != null && b != null && c != null) {
                    triples.add(floatArrayOf(a, b, c))
                    sawTriple = true
                    continue
                }
            }
            if (parts.size == 1 && !sawTriple) {
                field.toFloatOrNull()?.let { scalarsBefore.add(it) }
            }
        }

        if (triples.isEmpty()) return null

        // Температуры — последние до двух скаляров перед акселерометром
        // (ведущий counter, если есть, отбрасывается).
        val t1: Float?
        val t2: Float?
        when {
            scalarsBefore.size >= 2 -> {
                t1 = scalarsBefore[scalarsBefore.size - 2]
                t2 = scalarsBefore[scalarsBefore.size - 1]
            }
            scalarsBefore.size == 1 -> { t1 = scalarsBefore[0]; t2 = null }
            else -> { t1 = null; t2 = null }
        }
        return sensorData(t1, t2, triples.getOrNull(0), triples.getOrNull(1))
    }

    /** Плоский список чисел; раскладка по количеству. */
    private fun parseFlat(line: String): SensorData? {
        val nums = numberRegex.findAll(line).mapNotNull { it.value.toFloatOrNull() }.toList()
        return when (nums.size) {
            3 -> sensorData(null, null, floatArrayOf(nums[0], nums[1], nums[2]), null)
            4 -> sensorData(nums[0], null, floatArrayOf(nums[1], nums[2], nums[3]), null)
            6 -> sensorData(
                null, null,
                floatArrayOf(nums[0], nums[1], nums[2]),
                floatArrayOf(nums[3], nums[4], nums[5])
            )
            8 -> eight(nums, 0)
            9 -> eight(nums, 1) // ведущий counter
            else -> null
        }
    }

    /** Раскладка из 8 значений начиная с [off]: t1,t2,a1xyz,a2xyz. */
    private fun eight(nums: List<Float>, off: Int) = sensorData(
        nums[off], nums[off + 1],
        floatArrayOf(nums[off + 2], nums[off + 3], nums[off + 4]),
        floatArrayOf(nums[off + 5], nums[off + 6], nums[off + 7])
    )

    private fun sensorData(t1: Float?, t2: Float?, a1: FloatArray?, a2: FloatArray?) = SensorData(
        temperature1 = t1, temperature2 = t2,
        accel1X = a1?.get(0), accel1Y = a1?.get(1), accel1Z = a1?.get(2),
        accel2X = a2?.get(0), accel2Y = a2?.get(1), accel2Z = a2?.get(2)
    )
}
