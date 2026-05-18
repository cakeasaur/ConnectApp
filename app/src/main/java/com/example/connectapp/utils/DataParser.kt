package com.example.connectapp.utils

import com.example.connectapp.data.models.SensorData

/**
 * Парсит одну строку, прилетевшую с микроконтроллера, в [SensorData].
 *
 * Поддержанные форматы (case-insensitive):
 *   Verbose:        "TEMP: 23.50", "Temperature on I2C1: 28.5 C"
 *   Compact:        "T:28.5", "T=28.5"
 *   Labeled axes:   "X: 254  Y: 0  Z: 59"  ("AX:" / "AY:" / "AZ:" тоже работают)
 *   Firmware-CSV:   "counter;temp1;temp2;ax1,ay1,az1;ax2,ay2,az2;..."
 *                   например "567;29.5;29.5;0,0,0;0,0,1;0.00;0.01;"
 *                   → берётся ОБА термометра и ОБА акселерометра.
 *   Bare 4 числа:   "28.50,254,0,59" — только T1 + первый акселерометр.
 *   Bare 8 чисел:   "28.5,29.1,0,0,0,0,1,0" — оба сенсора, без меток.
 *
 * Возвращает null, если строка не содержит распознаваемых значений.
 */
object DataParser {

    // Verbose: "Temperature on I2C1: 28.5 C", "Temp: 23.5".
    // Decimal обязателен — иначе regex поймает '1' из "I2C1" как температуру.
    private val tempVerboseRegex = Regex("""(?i)temp(?:erature)?\b.*?(-?\d+\.\d+)""")

    // Compact: "T:28.5", "T=28.5". Привязан к началу строки или разделителю.
    private val tempCompactRegex = Regex("""(?i)(?:^|[\s,;>|])t[:\s=]+(-?\d+(?:\.\d+)?)""")

    // Labeled axes: "X: 254", "AX: 254", "Y:..", "Z:..".
    private val xRegex = Regex("""(?i)(?:^|[\s,;>|])a?x[:\s=]+(-?[\d.]+)""")
    private val yRegex = Regex("""(?i)(?:^|[\s,;>|])a?y[:\s=]+(-?[\d.]+)""")
    private val zRegex = Regex("""(?i)(?:^|[\s,;>|])a?z[:\s=]+(-?[\d.]+)""")

    // Firmware monitor CSV: "counter;temp1;temp2;ax1,ay1,az1;ax2,ay2,az2;..."
    // Захватываем оба термометра и оба акселерометра — раньше второй сенсор
    // молча терялся. Хвост (`.*`) допускает trailing fields/CRC/timestamp.
    private val firmwareMonitorRegex = Regex(
        """^\d+;(-?\d+\.\d+);(-?\d+(?:\.\d+)?);(-?\d+),(-?\d+),(-?\d+);(-?\d+),(-?\d+),(-?\d+);.*"""
    )

    // Generic bare format с 4 числами: "28.50,254,0,59" — только T1 + accel1.
    // Префикс — НЕ буквы/цифры/минус, иначе greedy сожрёт первую цифру температуры.
    private val bare4Regex = Regex(
        """^[^a-zA-Z\d\-]*(-?\d+\.\d+)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)\s*$"""
    )

    // Bare 8 чисел: "28.5,29.1,0,0,0,0,1,0" — оба сенсора без меток.
    private val bare8Regex = Regex(
        """^[^a-zA-Z\d\-]*(-?\d+\.\d+)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+""" +
        """(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+""" +
        """(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)[,\s;]+(-?\d+(?:\.\d+)?)\s*$"""
    )

    fun parse(line: String): SensorData? {
        val lower = line.lowercase()
        // Калибровочные строки нельзя интерпретировать как данные сенсора.
        val isCalib = lower.contains("calib")

        // 1. Labeled-форматы — самые однозначные, проверяем первыми.
        val temp = if (!isCalib) {
            tempVerboseRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
                ?: tempCompactRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        } else null
        val x = xRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val y = yRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val z = zRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()

        if (temp != null || x != null || y != null || z != null) {
            return SensorData(
                temperature1 = temp,
                accel1X = x, accel1Y = y, accel1Z = z
            )
        }

        if (isCalib) return null

        // 2. Firmware CSV — оба сенсора.
        firmwareMonitorRegex.find(line)?.toEightSensors()?.let { return it }

        // 3. Bare 8 чисел — пробуем раньше bare4, иначе короткий regex
        // успешно сматчится по первым 4 числам.
        bare8Regex.find(line)?.toEightSensors()?.let { return it }

        // 4. Bare 4 числа — temp + первый акселерометр.
        bare4Regex.find(line)?.let { m ->
            val t1 = m.f(1) ?: return@let
            return SensorData(
                temperature1 = t1,
                accel1X = m.f(2), accel1Y = m.f(3), accel1Z = m.f(4)
            )
        }

        return null
    }

    /** Группа [i] → Float? через одну точку, чтобы не дублировать выражение. */
    private fun MatchResult.f(i: Int): Float? = groupValues[i].toFloatOrNull()

    /** "T1;T2;A1X;A1Y;A1Z;A2X;A2Y;A2Z" — общая схема для firmware-CSV и bare8. */
    private fun MatchResult.toEightSensors(): SensorData? {
        val t1 = f(1) ?: return null
        return SensorData(
            temperature1 = t1, temperature2 = f(2),
            accel1X = f(3), accel1Y = f(4), accel1Z = f(5),
            accel2X = f(6), accel2Y = f(7), accel2Z = f(8)
        )
    }
}
