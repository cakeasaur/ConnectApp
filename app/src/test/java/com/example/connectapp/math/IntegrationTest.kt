package com.example.connectapp.math

import com.example.connectapp.data.models.TimedPoint
import org.junit.Assert.*
import org.junit.Test

class IntegrationTest {

    @Test
    fun `detrend removes constant offset`() {
        val points = (0 until 10).map { i -> TimedPoint(i * 100L, 5f) }
        val result = Integration.detrend(points)
        for (p in result) assertEquals(0f, p.value, 1e-5f)
    }

    @Test
    fun `detrend removes linear trend`() {
        val points = (0 until 10).map { i -> TimedPoint(i * 100L, i.toFloat()) }
        val result = Integration.detrend(points)
        assertEquals(0f, result.first().value, 1e-4f)
        assertEquals(0f, result.last().value, 1e-4f)
    }

    @Test
    fun `integrate single point returns zero`() {
        val points = listOf(TimedPoint(0L, 1f))
        val result = Integration.integrate(points)
        assertEquals(1, result.size)
        assertEquals(0f, result[0].value, 1e-6f)
    }

    @Test
    fun `integrate pulse with zero endpoints accumulates positive area`() {
        // Pulse [0, 1, 2, 1, 0]: first==last==0, so detrend has slope=0
        val points = listOf(
            TimedPoint(0L, 0f),
            TimedPoint(100L, 1f),
            TimedPoint(200L, 2f),
            TimedPoint(300L, 1f),
            TimedPoint(400L, 0f)
        )
        val result = Integration.integrate(points)
        assertEquals(5, result.size)
        // Trapezoid integral = 0.4, then detrend subtracts the linear slope (0→0.4 over 400ms),
        // yielding symmetric [-0.05, 0, 0.05, 0] around zero.
        val maxVal = result.maxOf { it.value }
        assertEquals(0.05f, maxVal, 1e-4f)
    }

    @Test
    fun `integrate constant signal returns near zero after detrend`() {
        // Constant 1.0 over 1 second (10 samples at 100ms). detrend before integration
        // subtracts the flat baseline (slope=0, offset=1.0), yielding all-zero input.
        // Integral of zero is zero. Second detrend is a no-op. No drift accumulates.
        val points = (0 until 10).map { i -> TimedPoint(i * 100L, 1f) }
        val result = Integration.integrate(points)
        assertEquals(10, result.size)
        for (p in result) assertEquals(0f, p.value, 1e-5f)
    }

    @Test
    fun `timestamps are preserved after integration`() {
        val points = listOf(
            TimedPoint(1000L, 0f),
            TimedPoint(1100L, 1f),
            TimedPoint(1200L, 0f)
        )
        val result = Integration.integrate(points)
        assertEquals(1000L, result[0].t)
        assertEquals(1100L, result[1].t)
        assertEquals(1200L, result[2].t)
    }
}
