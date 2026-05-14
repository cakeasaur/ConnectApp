package com.example.connectapp.data.models

/**
 * Снимок данных с двух сенсоров платы (T1/T2 + два акселерометра).
 * Любое поле может быть null, если соответствующий сенсор не присутствовал
 * в очередной строке (например, прислан только labeled "TEMP: 23.5").
 */
data class SensorData(
    val temperature1: Float? = null,
    val temperature2: Float? = null,
    val accel1X: Float? = null,
    val accel1Y: Float? = null,
    val accel1Z: Float? = null,
    val accel2X: Float? = null,
    val accel2Y: Float? = null,
    val accel2Z: Float? = null
) {
    /** Возвращает копию, в которой не-null поля [other] перетирают текущие. */
    fun merge(other: SensorData) = copy(
        temperature1 = other.temperature1 ?: temperature1,
        temperature2 = other.temperature2 ?: temperature2,
        accel1X = other.accel1X ?: accel1X,
        accel1Y = other.accel1Y ?: accel1Y,
        accel1Z = other.accel1Z ?: accel1Z,
        accel2X = other.accel2X ?: accel2X,
        accel2Y = other.accel2Y ?: accel2Y,
        accel2Z = other.accel2Z ?: accel2Z
    )

    val hasAnyTemp get() = temperature1 != null || temperature2 != null
    val hasAnyAccel get() =
        accel1X != null || accel1Y != null || accel1Z != null ||
        accel2X != null || accel2Y != null || accel2Z != null
}
