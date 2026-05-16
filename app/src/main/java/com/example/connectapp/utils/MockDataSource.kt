package com.example.connectapp.utils

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Симулятор потока, который выдаёт ту же строку платы PIC24FJ128GB106 как живая.
 * Сначала эмитит boot-меню (со «Auto-starting in 5 seconds…»), затем — реалистичный
 * CSV-поток `counter;temp1;temp2;ax1,ay1,az1;ax2,ay2,az2;` на частоте ~10 Hz.
 *
 * Температура — синусоида ≈ 25°C ± 2°C с лёгким шумом.
 * Акселерометры — синусоидальные X/Y, Z ≈ 1g + шум (плата лежит).
 */
object MockDataSource {

    /** Полный поток: boot-меню → пауза → CSV до отмены. */
    fun stream(periodMs: Long = 100L): Flow<String> = callbackFlow {
        // 1) Boot-меню — повторяет реальный дамп с платы.
        trySend(BOOT_BANNER)
        delay(800)
        trySend("Starting enhanced monitoring at 10 Hz\n")
        delay(300)

        // 2) CSV-поток.
        var counter = 0
        val t0 = System.currentTimeMillis()
        try {
            while (true) {
                val ts = (System.currentTimeMillis() - t0) / 1000.0
                val temp1 = 25.0 + 2.0 * sin(ts * 0.3) + Random.nextDouble(-0.05, 0.05)
                val temp2 = 25.0 + 1.5 * sin(ts * 0.3 + PI / 6) + Random.nextDouble(-0.05, 0.05)

                // Акселерометр #1: лёгкое покачивание + 1g по Z
                val ax1 = (500 * sin(ts * 1.7)).toInt()
                val ay1 = (500 * cos(ts * 1.7)).toInt()
                val az1 = 1000 + Random.nextInt(-20, 20)

                // Акселерометр #2: другой паттерн (как будто на второй стороне платы)
                val ax2 = (300 * sin(ts * 2.3 + PI / 4)).toInt()
                val ay2 = (300 * cos(ts * 2.3 + PI / 4)).toInt()
                val az2 = 1000 + Random.nextInt(-15, 15)

                val line = "%d;%.2f;%.2f;%d,%d,%d;%d,%d,%d;\n".format(
                    java.util.Locale.ROOT,
                    counter, temp1, temp2, ax1, ay1, az1, ax2, ay2, az2
                )
                trySend(line)
                counter++
                delay(periodMs)
            }
        } finally {
            // ничего, awaitClose ниже остановит цикл при отмене.
        }
        awaitClose { /* отмена корутины автоматически прекратит while(true) */ }
    }

    private const val BOOT_BANNER =
        "================================================\n" +
        "   PIC24FJ128GB106 Monitoring System (MOCK)\n" +
        "================================================\n" +
        "Buttons initialized\n" +
        "\n" +
        "================================================\n" +
        "         PIC24FJ128GB106 Sensor Monitor\n" +
        "================================================\n" +
        "Testing I2C1 devices... OK\n" +
        "Testing I2C2 devices... OK\n" +
        "Testing I2C3 EEPROM... OK\n" +
        "\n" +
        "All sensors detected!\n" +
        "Loading calibration data from EEPROM... OK\n" +
        "Calibration data successfully loaded...\n"
}
