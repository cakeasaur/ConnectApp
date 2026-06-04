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

        // 2) CSV-поток. Сигналы подобраны так, чтобы математика показывала
        //    осмысленные результаты:
        //    - T1, T2: разница ~3°C → ненулевой тепловой поток (Фурье).
        //    - ax1, ax2: общая вибрация 2.0 Hz + сдвиг фазы 200мс между
        //      датчиками → cross-correlation находит лаг = 2 отсчёта (при 10 Hz).
        //    - az ≈ 1000 LSB (гравитация) → tilt-индикатор показывает 0°.
        var counter = 0
        val t0 = System.currentTimeMillis()
        try {
            while (true) {
                val ts = (System.currentTimeMillis() - t0) / 1000.0

                // Температура: T1 повыше (источник тепла), T2 пониже → q > 0.
                val temp1 = 27.0 + 0.5 * sin(ts * 0.3) + Random.nextDouble(-0.05, 0.05)
                val temp2 = 24.0 + 0.5 * sin(ts * 0.3 + PI / 6) + Random.nextDouble(-0.05, 0.05)

                // Общая вибрация — синус 2 Hz, амплитуда 80 LSB.
                // Поверх — медленное покачивание 0.27 Hz, амплитуда 400 LSB.
                val vib = 80.0 * sin(2.0 * PI * 2.0 * ts)
                val vibDelayed = 80.0 * sin(2.0 * PI * 2.0 * (ts - 0.2)) // 200мс лаг

                // Акселерометр #1
                val ax1 = (400 * sin(ts * 1.7) + vib + Random.nextDouble(-5.0, 5.0)).toInt()
                val ay1 = (400 * cos(ts * 1.7)).toInt()
                val az1 = 1000 + Random.nextInt(-20, 20)

                // Акселерометр #2 — общая вибрация с задержкой + свой паттерн +
                // постоянное смещение -5 LSB на ax (демонстрация Kalman fusion).
                val ax2 = (250 * sin(ts * 2.3 + PI / 4) + vibDelayed - 5 + Random.nextDouble(-7.0, 7.0)).toInt()
                val ay2 = (250 * cos(ts * 2.3 + PI / 4)).toInt()
                val az2 = 1000 + Random.nextInt(-15, 15)

                val line = "%d;%.2f;%.2f;%d,%d,%d;%d,%d,%d;\n".format(
                    java.util.Locale.ROOT,
                    counter, temp1, temp2, ax1, ay1, az1, ax2, ay2, az2
                )
                trySend(line)
                // Демо динамических каналов: раз в 10 отсчётов плата шлёт ещё и
                // именованные метки (напряжение, обороты) — авто-детект каналов.
                if (counter % 10 == 0) {
                    val vbat = 3.7 + 0.1 * sin(ts * 0.5)
                    val rpm = (1200 + 300 * sin(ts * 0.8)).toInt()
                    trySend("vbat=%.2f rpm=%d\n".format(java.util.Locale.ROOT, vbat, rpm))
                }
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
