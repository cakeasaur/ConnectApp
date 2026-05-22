package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 *
 * Внутри хранится в [ArrayDeque] (амортизированно O(1) на push+pop), а в
 * публичный StateFlow эмитим snapshot-копией. Старая реализация
 * `(it + point).takeLast(MAX)` делала 2 alloc'а на каждый отсчёт и при
 * 10 Hz создавала ощутимое давление на GC.
 */
object SensorDataBus {

    /** Размер окна по точкам. ~10 минут при 10 Hz / ~5 минут при 20 Hz. */
    const val MAX_POINTS = 600

    private class Channel {
        val deque = ArrayDeque<TimedPoint>(MAX_POINTS + 1)
        val flow = MutableStateFlow<List<TimedPoint>>(emptyList())
    }

    private val temp1Ch = Channel()
    private val temp2Ch = Channel()
    private val a1xCh = Channel(); private val a1yCh = Channel(); private val a1zCh = Channel()
    private val a2xCh = Channel(); private val a2yCh = Channel(); private val a2zCh = Channel()

    val temp1: StateFlow<List<TimedPoint>> = temp1Ch.flow.asStateFlow()
    val temp2: StateFlow<List<TimedPoint>> = temp2Ch.flow.asStateFlow()
    val accel1X: StateFlow<List<TimedPoint>> = a1xCh.flow.asStateFlow()
    val accel1Y: StateFlow<List<TimedPoint>> = a1yCh.flow.asStateFlow()
    val accel1Z: StateFlow<List<TimedPoint>> = a1zCh.flow.asStateFlow()
    val accel2X: StateFlow<List<TimedPoint>> = a2xCh.flow.asStateFlow()
    val accel2Y: StateFlow<List<TimedPoint>> = a2yCh.flow.asStateFlow()
    val accel2Z: StateFlow<List<TimedPoint>> = a2zCh.flow.asStateFlow()

    fun addTemperature(slot: Int, value: Float, ts: Long = System.currentTimeMillis()) {
        push(if (slot == 2) temp2Ch else temp1Ch, TimedPoint(ts, value))
    }

    fun addAccel(slot: Int, x: Float, y: Float, z: Float, ts: Long = System.currentTimeMillis()) {
        if (slot == 2) {
            push(a2xCh, TimedPoint(ts, x))
            push(a2yCh, TimedPoint(ts, y))
            push(a2zCh, TimedPoint(ts, z))
        } else {
            push(a1xCh, TimedPoint(ts, x))
            push(a1yCh, TimedPoint(ts, y))
            push(a1zCh, TimedPoint(ts, z))
        }
    }

    private fun push(ch: Channel, point: TimedPoint) {
        // flow.value выставляется ВНУТРИ synchronized — иначе два потока (BT + USB
        // одновременно) могут взять снимки в правильном порядке, но присвоить
        // flow.value в обратном: подписчики увидят откат на одну точку назад.
        com.example.connectapp.utils.PerfTrace.measure("bus.push") {
            synchronized(ch.deque) {
                ch.deque.addLast(point)
                if (ch.deque.size > MAX_POINTS) ch.deque.removeFirst()
                ch.flow.value = ch.deque.toList()
            }
        }
    }

    /**
     * Поколение шины. Инкрементируется при [clear] — потребители (например,
     * stateful Kalman в MathSection) используют это как ключ для сброса
     * собственного накопленного state.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun clear() {
        for (ch in arrayOf(temp1Ch, temp2Ch, a1xCh, a1yCh, a1zCh, a2xCh, a2yCh, a2zCh)) {
            synchronized(ch.deque) { ch.deque.clear() }
            ch.flow.value = emptyList()
        }
        _generation.value += 1
    }
}
