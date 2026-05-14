package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Точка ([t], value) — храним метку времени, а не индекс, чтобы графики
 * корректно отображали неравномерные интервалы между пакетами.
 */
data class TimedPoint(val t: Long, val value: Float)

/**
 * Шина временных рядов сенсоров. BluetoothViewModel пишет, GraphActivity читает.
 *
 * Окно фиксированное ([MAX_POINTS]), старые точки выбрасываются — иначе при
 * долгом monitor-режиме список вырастет в гигабайты.
 */
object SensorDataBus {

    /** Размер окна по точкам. ~10 минут при 10 Hz / ~5 минут при 20 Hz. */
    const val MAX_POINTS = 600

    private val _temp1 = MutableStateFlow<List<TimedPoint>>(emptyList())
    val temp1: StateFlow<List<TimedPoint>> = _temp1.asStateFlow()

    private val _temp2 = MutableStateFlow<List<TimedPoint>>(emptyList())
    val temp2: StateFlow<List<TimedPoint>> = _temp2.asStateFlow()

    private val _accel1X = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel1X: StateFlow<List<TimedPoint>> = _accel1X.asStateFlow()
    private val _accel1Y = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel1Y: StateFlow<List<TimedPoint>> = _accel1Y.asStateFlow()
    private val _accel1Z = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel1Z: StateFlow<List<TimedPoint>> = _accel1Z.asStateFlow()

    private val _accel2X = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel2X: StateFlow<List<TimedPoint>> = _accel2X.asStateFlow()
    private val _accel2Y = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel2Y: StateFlow<List<TimedPoint>> = _accel2Y.asStateFlow()
    private val _accel2Z = MutableStateFlow<List<TimedPoint>>(emptyList())
    val accel2Z: StateFlow<List<TimedPoint>> = _accel2Z.asStateFlow()

    fun addTemperature(slot: Int, value: Float, ts: Long = System.currentTimeMillis()) {
        val point = TimedPoint(ts, value)
        val target = if (slot == 2) _temp2 else _temp1
        target.update { (it + point).takeLast(MAX_POINTS) }
    }

    fun addAccel(slot: Int, x: Float, y: Float, z: Float, ts: Long = System.currentTimeMillis()) {
        val px = TimedPoint(ts, x); val py = TimedPoint(ts, y); val pz = TimedPoint(ts, z)
        if (slot == 2) {
            _accel2X.update { (it + px).takeLast(MAX_POINTS) }
            _accel2Y.update { (it + py).takeLast(MAX_POINTS) }
            _accel2Z.update { (it + pz).takeLast(MAX_POINTS) }
        } else {
            _accel1X.update { (it + px).takeLast(MAX_POINTS) }
            _accel1Y.update { (it + py).takeLast(MAX_POINTS) }
            _accel1Z.update { (it + pz).takeLast(MAX_POINTS) }
        }
    }

    fun clear() {
        _temp1.value = emptyList()
        _temp2.value = emptyList()
        _accel1X.value = emptyList()
        _accel1Y.value = emptyList()
        _accel1Z.value = emptyList()
        _accel2X.value = emptyList()
        _accel2Y.value = emptyList()
        _accel2Z.value = emptyList()
    }
}
