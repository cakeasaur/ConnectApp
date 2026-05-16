package com.example.connectapp.math

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ориентация в пространстве из показаний трёхосевого акселерометра.
 *
 * Принцип: вектор гравитации (0, 0, g) в системе координат датчика
 * раскладывается на 3 проекции. По их соотношению можно восстановить
 * наклон датчика относительно горизонта.
 *
 * Допущение: устройство НЕ ускоряется — иначе линейное ускорение
 * добавится к гравитации и pitch/roll врут. Для динамики нужен
 * комплементарный фильтр с гироскопом (которого тут нет).
 */
object Orientation {

    private const val RAD_TO_DEG = (180.0 / PI).toFloat()

    /**
     * Pitch (тангаж) — наклон вперёд/назад вокруг оси Y.
     * Диапазон [-90°, +90°]. 0° = горизонт.
     *
     * Формула: pitch = atan2(-ax, sqrt(ay² + az²))
     */
    fun pitchDeg(ax: Float, ay: Float, az: Float): Float =
        atan2(-ax, sqrt(ay * ay + az * az)) * RAD_TO_DEG

    /**
     * Roll (крен) — наклон влево/вправо вокруг оси X.
     * Диапазон [-180°, +180°]. 0° = горизонт.
     *
     * Формула: roll = atan2(ay, az). ax в формулу не входит — оставлен в
     * сигнатуре для симметрии с [pitchDeg]: вызывающий передаёт весь вектор.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rollDeg(ax: Float, ay: Float, az: Float): Float =
        atan2(ay, az) * RAD_TO_DEG

    /** Модуль вектора ускорения. При покое ≈ g (≈ 9.81 м/с² или ≈ 1000 LSB у MEMS). */
    fun magnitude(ax: Float, ay: Float, az: Float): Float =
        sqrt(ax * ax + ay * ay + az * az)
}
