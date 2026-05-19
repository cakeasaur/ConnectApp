package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class Kalman1DTest {

    @Test
    fun `identical measurements converge to that value`() {
        val kalman = Kalman1D(processVar = 0.01f, measVar1 = 1f, measVar2 = 1f)
        val target = 5f
        repeat(50) { kalman.update(target, target) }
        assertEquals(target, kalman.estimate, 0.01f)
    }

    @Test
    fun `low noise sensor dominates over high noise sensor`() {
        val precise = 10f
        val noisy = 100f
        val kalman = Kalman1D(processVar = 0.001f, measVar1 = 0.01f, measVar2 = 100f)
        repeat(50) { kalman.update(precise, noisy) }
        assertTrue(abs(kalman.estimate - precise) < abs(kalman.estimate - noisy))
    }

    @Test
    fun `reset restores initial state`() {
        val kalman = Kalman1D()
        val initialCovariance = kalman.covariance
        val initialEstimate = kalman.estimate
        repeat(20) { kalman.update(50f, 50f) }
        assertTrue(kalman.covariance < initialCovariance)
        kalman.reset()
        assertEquals(initialCovariance, kalman.covariance, 1e-6f)
        assertEquals(initialEstimate, kalman.estimate, 1e-6f)
    }
}
