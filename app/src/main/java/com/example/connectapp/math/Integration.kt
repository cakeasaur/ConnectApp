package com.example.connectapp.math

import kotlin.math.PI

/**
 * Численное интегрирование ускорения для получения скорости и перемещения.
 *
 * Базовая идея: v(t) = ∫a dt, s(t) = ∫v dt — методом трапеций.
 *
 * Проблема: любая постоянная составляющая (DC bias, шум калибровки) после
 * двойного интегрирования превращается в квадратичный рост — на 60 секундах
 * "перемещение" улетает в километры. Лечится **high-pass фильтром** на
 * каждом шаге интегрирования: убирает медленный drift, оставляет AC-вибрации.
 *
 * Cutoff 0.5 Hz по умолчанию — режет всё ниже полугерца (включая DC bias).
 * Если у тебя реальный медленный процесс (5..60 секунд) — нужно ставить
 * cutoff в 0.05 Hz, иначе HPF съест полезный сигнал.
 */
object Integration {

    /**
     * Интегрирует [accel] (м/с² или g — какое подал, такое и получишь) по
     * времени методом трапеций + high-pass фильтр первого порядка.
     *
     * @param accel массив отсчётов ускорения
     * @param dtSec шаг по времени (1/fs)
     * @param hpfCutoffHz частота среза HPF, чтобы убить drift. 0 = выключить.
     * @return массив той же длины со значениями интеграла (скорость).
     */
    fun integrate(accel: FloatArray, dtSec: Float, hpfCutoffHz: Float = 0.5f): FloatArray {
        if (accel.isEmpty()) return FloatArray(0)
        val v = FloatArray(accel.size)

        // 1) Трапеции: v[i] = v[i-1] + (a[i-1] + a[i]) / 2 * dt
        for (i in 1 until accel.size) {
            v[i] = v[i - 1] + (accel[i - 1] + accel[i]) * 0.5f * dtSec
        }

        if (hpfCutoffHz <= 0f) return v

        // 2) HPF первого порядка: y[n] = α (y[n-1] + x[n] - x[n-1])
        //    α = RC / (RC + dt), где RC = 1 / (2π fc)
        val rc = 1f / (2f * PI.toFloat() * hpfCutoffHz)
        val alpha = rc / (rc + dtSec)
        val out = FloatArray(v.size)
        out[0] = 0f
        for (i in 1 until v.size) {
            out[i] = alpha * (out[i - 1] + v[i] - v[i - 1])
        }
        return out
    }

    /**
     * Двойное интегрирование: accel → velocity → displacement.
     * Drift гасится HPF на каждом этапе (рекомендация Brüel & Kjær).
     */
    fun doubleIntegrate(accel: FloatArray, dtSec: Float, hpfCutoffHz: Float = 0.5f): FloatArray {
        val v = integrate(accel, dtSec, hpfCutoffHz)
        return integrate(v, dtSec, hpfCutoffHz)
    }
}
