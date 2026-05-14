package com.example.connectapp.utils

import com.example.connectapp.data.models.SensorData

/**
 * Parses a single text line from the microcontroller into [SensorData].
 *
 * Supported formats (case-insensitive):
 *   Temperature:
 *     Verbose  — "TEMP: 23.50", "Temperature on I2C1: 28.5 C"
 *     Compact  — "T:28.5", "T=28.5"  (at start or after separator)
 *   Accel axes (labeled):
 *     "X: 254", "AX: 254", "Y: 0", "Z: 59"
 *   Firmware monitor CSV (semicolon-delimited):
 *     "counter;temp1;temp2;ax1,ay1,az1;ax2,ay2,az2;..."
 *     e.g. "567;29.5;29.5;0,0,0;0,0,1;0.00;0.01;"
 *     → uses temp1 and first accelerometer (I2C1).
 *   Generic bare format:
 *     "28.50,254,0,59" or "28.50 254 0 59" (temp + X Y Z, no labels)
 *
 * Returns null if the line contains no recognisable sensor values.
 */
object DataParser {

    // Verbose: "Temperature on I2C1: 28.5 C", "Temp: 23.5"
    // Decimal is REQUIRED — prevents matching stray integers like the "1" in "I2C1".
    private val tempVerboseRegex = Regex("""(?i)temp(?:erature)?\b.*?(-?[\d]+\.[\d]+)""")

    // Compact: "T:28.5", "T=28.5"
    // Anchored to start-of-line or separator chars.
    private val tempCompactRegex = Regex("""(?i)(?:^|[\s,;>|])t[:\s=]+(-?[\d]+(?:\.[\d]+)?)""")

    // Labeled axis regexes — "X: 254", "AX: 254", etc.
    private val xRegex = Regex("""(?i)(?:^|[\s,;>|])a?x[:\s=]+(-?[\d.]+)""")
    private val yRegex = Regex("""(?i)(?:^|[\s,;>|])a?y[:\s=]+(-?[\d.]+)""")
    private val zRegex = Regex("""(?i)(?:^|[\s,;>|])a?z[:\s=]+(-?[\d.]+)""")

    // Firmware monitor format: "counter;temp1;temp2;ax,ay,az;..."
    // counter = any integer, temp1 = float with decimal,
    // second field (temp2) skipped, third field = "X,Y,Z" accel integers.
    private val firmwareMonitorRegex = Regex(
        """^\d+;(-?[\d]+\.[\d]+);[^;]*;(-?[\d]+),(-?[\d]+),(-?[\d]+);"""
    )

    // Generic bare format: "28.50,254,0,59" (no labels, 4 values, temp must have decimal).
    // Префикс — что угодно КРОМЕ букв, цифр и минуса. Иначе greedy `[^a-zA-Z]*`
    // сжирает первую цифру температуры (баг: «28.50» парсилось как «8.50»).
    private val bareMonitorRegex = Regex(
        """^[^a-zA-Z\d\-]*(-?[\d]+\.[\d]+)[,\s;]+(-?[\d]+(?:\.[\d]+)?)[,\s;]+(-?[\d]+(?:\.[\d]+)?)[,\s;]+(-?[\d]+(?:\.[\d]+)?)\s*$"""
    )

    fun parse(line: String): SensorData? {
        val lower = line.lowercase()
        // Skip calibration lines (e.g. "Calibration - Temp: 0.0 to 0.0 C").
        val isCalibLine = lower.contains("calib")

        // 1. Try labeled formats first.
        val temp = if (!isCalibLine) {
            tempVerboseRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
                ?: tempCompactRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        } else null
        val x = xRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val y = yRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val z = zRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()

        if (temp != null || x != null || y != null || z != null) {
            return SensorData(temp, x, y, z)
        }

        if (isCalibLine) return null

        // 2. Firmware semicolon monitor format: "567;29.5;29.5;0,0,0;0,0,1;0.00;0.01;"
        firmwareMonitorRegex.find(line)?.let { m ->
            val ft = m.groupValues[1].toFloatOrNull() ?: return@let
            val fx = m.groupValues[2].toFloatOrNull()
            val fy = m.groupValues[3].toFloatOrNull()
            val fz = m.groupValues[4].toFloatOrNull()
            return SensorData(ft, fx, fy, fz)
        }

        // 3. Generic bare format: "28.50 254 0 59"
        bareMonitorRegex.find(line)?.let { m ->
            val bt = m.groupValues[1].toFloatOrNull() ?: return@let
            val bx = m.groupValues[2].toFloatOrNull()
            val by = m.groupValues[3].toFloatOrNull()
            val bz = m.groupValues[4].toFloatOrNull()
            return SensorData(bt, bx, by, bz)
        }

        return null
    }
}
