package com.example.connectapp.math

/**
 * Скалярный Kalman-фильтр со слиянием двух источников измерения одной величины.
 *
 * Сценарий в твоей лабе: два акселерометра меряют ускорение одной точки
 * (резервирование). У каждого свой шум измерения. Kalman даёт оптимальную
 * по MSE оценку истинного значения с учётом обеих неопределённостей.
 *
 * Модель:
 *   state:    x[k] = x[k-1] + w,   w ~ N(0, processVar)
 *   measure:  z1 = x + v1,         v1 ~ N(0, measVar1)
 *             z2 = x + v2,         v2 ~ N(0, measVar2)
 *
 * Обновляем последовательно: сначала z1, потом z2 — эквивалентно
 * совместному обновлению при некоррелированных шумах.
 *
 * Если знаешь точные дисперсии шумов своих датчиков — поставь их в
 * measVar1/measVar2. Если нет — оставь 1.0 (равные веса). processVar
 * управляет "доверием к модели": маленькое = сильное сглаживание,
 * большое = быстрый отклик на изменения.
 */
class Kalman1D(
    private val processVar: Float = 0.01f,
    private val measVar1: Float = 1f,
    private val measVar2: Float = 1f
) {
    /** Текущая оценка состояния. */
    var estimate: Float = 0f
        private set

    /** Текущая ковариация ошибки. Меньше — увереннее оценка. */
    var covariance: Float = 1f
        private set

    /** Обновить оценку двумя измерениями. Возвращает новую оценку. */
    fun update(z1: Float, z2: Float): Float {
        // Predict: state неизменно, ковариация растёт на processVar
        covariance += processVar

        // Update 1: измерение от датчика 1
        val k1 = covariance / (covariance + measVar1)
        estimate += k1 * (z1 - estimate)
        covariance *= (1f - k1)

        // Update 2: измерение от датчика 2
        val k2 = covariance / (covariance + measVar2)
        estimate += k2 * (z2 - estimate)
        covariance *= (1f - k2)

        return estimate
    }

    /** Сбросить состояние к начальным значениям. */
    fun reset() {
        estimate = 0f
        covariance = 1f
    }
}
