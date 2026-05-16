package com.example.connectapp.math

/**
 * Тепловой поток через одномерное тело по закону Фурье.
 *
 *   q = -k · dT/dx     [Вт/м²]
 *
 * где k — теплопроводность материала, dT/dx — градиент температуры.
 *
 * Применение в твоей лабе: два температурных датчика на разных концах
 * стержня/пластины → измеряем разницу → считаем тепловой поток в реальном
 * времени. Полезно для:
 *   - оценки эффективности радиатора,
 *   - детектирования теплового моста в стене,
 *   - калориметрии простых экспериментов.
 *
 * Знак: q > 0 → поток от датчика 1 к датчику 2 (T1 > T2).
 *       q < 0 → поток в обратную сторону.
 */
object HeatFlux {

    /** Теплопроводности типичных материалов (Вт/(м·К)). */
    object Conductivity {
        const val COPPER = 401f
        const val ALUMINUM = 237f
        const val STEEL = 50f
        const val GLASS = 1.05f
        const val WATER = 0.6f
        const val WOOD = 0.13f
        const val AIR = 0.026f
    }

    /**
     * @param t1 температура датчика 1 (°C или K — главное чтобы единицы совпадали с t2)
     * @param t2 температура датчика 2
     * @param distanceM расстояние между датчиками (м)
     * @param conductivity k материала (Вт/м·К). По умолчанию — медь.
     * @return удельный тепловой поток в Вт/м². NaN если distanceM ≤ 0.
     */
    fun compute(
        t1: Float,
        t2: Float,
        distanceM: Float,
        conductivity: Float = Conductivity.COPPER
    ): Float {
        if (distanceM <= 0f) return Float.NaN
        // dT/dx по двум точкам: (T2 - T1) / Δx
        val gradient = (t2 - t1) / distanceM
        return -conductivity * gradient
    }
}
