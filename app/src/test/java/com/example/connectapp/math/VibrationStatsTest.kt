package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class VibrationStatsTest {

    @Test
    fun `zeros array gives zero rms and peak`() {
        val stats = computeVibrationStats(FloatArray(100) { 0f })
        assertEquals(0f, stats.rms, 1e-6f)
        assertEquals(0f, stats.peak, 1e-6f)
    }

    @Test
    fun `sine wave rms is amplitude over sqrt 2`() {
        val n = 1000
        val amplitude = 3f
        val input = FloatArray(n) { i -> amplitude * sin(2.0 * PI * 10.0 * i / n).toFloat() }
        val stats = computeVibrationStats(input)
        val expected = amplitude / sqrt(2f)
        assertEquals(expected, stats.rms, expected * 0.01f)
    }

    @Test
    fun `impulse kurtosis is greater than sine kurtosis`() {
        val n = 1000
        val sineSignal = FloatArray(n) { i -> sin(2.0 * PI * 5.0 * i / n).toFloat() }
        val impulseSignal = FloatArray(n) { 0f }.also { arr ->
            for (i in 0 until n step 100) arr[i] = 10f
        }
        val sineStats = computeVibrationStats(sineSignal)
        val impulseStats = computeVibrationStats(impulseSignal)
        assertTrue(impulseStats.kurtosis > sineStats.kurtosis)
    }

    @Test
    fun `crest factor is peak over rms`() {
        val n = 1000
        val amplitude = 4f
        val input = FloatArray(n) { i -> amplitude * sin(2.0 * PI * 5.0 * i / n).toFloat() }
        val stats = computeVibrationStats(input)
        val expected = stats.peak / stats.rms
        assertEquals(expected, stats.crest, 0.001f)
    }
}
