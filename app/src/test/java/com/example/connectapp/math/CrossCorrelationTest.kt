package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CrossCorrelationTest {

    @Test
    fun `identical signals have best lag zero and correlation near one`() {
        val n = 100
        val signal = FloatArray(n) { i -> sin(2.0 * PI * 3 * i / n).toFloat() }
        val maxLag = 20
        val corr = CrossCorrelation.normalized(signal, signal, maxLag)
        val (lag, value) = CrossCorrelation.bestLag(corr, maxLag)
        assertEquals(0, lag)
        assertEquals(1f, value, 0.01f)
    }

    @Test
    fun `signal ahead of x has positive best lag`() {
        val n = 200
        val lead = 5
        val maxLag = 20
        val x = FloatArray(n) { i -> sin(2.0 * PI * 3 * i / n).toFloat() }
        // y is x shifted forward (y leads x by lead samples)
        val y = FloatArray(n) { i -> if (i + lead < n) x[i + lead] else 0f }
        val corr = CrossCorrelation.normalized(x, y, maxLag)
        val (lag, _) = CrossCorrelation.bestLag(corr, maxLag)
        assertEquals(lead, lag)
    }

    @Test
    fun `anti-phase signals have negative correlation at lag zero`() {
        val n = 100
        val maxLag = 20
        val x = FloatArray(n) { i -> sin(2.0 * PI * 5 * i / n).toFloat() }
        val y = FloatArray(n) { i -> -x[i] }
        val corr = CrossCorrelation.normalized(x, y, maxLag)
        val zeroLagIdx = maxLag
        assertTrue(corr[zeroLagIdx] < -0.9f)
    }

    @Test
    fun `empty input returns empty correlation`() {
        val result = CrossCorrelation.normalized(FloatArray(0), FloatArray(0), 5)
        assertEquals(0, result.size)
    }
}
