package com.example.connectapp.data.models

data class SensorData(
    val temperature: Float? = null,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null
) {
    /** Returns a copy with non-null fields from [other] overwriting this instance. */
    fun merge(other: SensorData) = copy(
        temperature = other.temperature ?: temperature,
        accelX = other.accelX ?: accelX,
        accelY = other.accelY ?: accelY,
        accelZ = other.accelZ ?: accelZ
    )
}
