package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test

class HeatFluxTest {

    @Test
    fun `copper flux matches analytical result`() {
        // k=401, T1=100, T2=50, d=0.01
        // q = -401 * (50-100)/0.01 = -401 * -5000 = 2_005_000
        val flux = HeatFlux.compute(t1 = 100f, t2 = 50f, distanceM = 0.01f, conductivity = HeatFlux.Conductivity.COPPER)
        assertEquals(2_005_000f, flux, 100f)
    }

    @Test
    fun `flux is positive when T1 greater than T2`() {
        val flux = HeatFlux.compute(100f, 50f, 0.01f)
        assertTrue(flux > 0f)
    }

    @Test
    fun `flux is negative when T1 less than T2`() {
        val flux = HeatFlux.compute(50f, 100f, 0.01f)
        assertTrue(flux < 0f)
    }

    @Test
    fun `zero distance returns NaN`() {
        assertTrue(HeatFlux.compute(100f, 50f, 0f).isNaN())
    }

    @Test
    fun `flux is zero when temperatures are equal`() {
        assertEquals(0f, HeatFlux.compute(50f, 50f, 0.01f), 1e-10f)
    }

    @Test
    fun `all material presets give nonzero flux when T1 not equal T2`() {
        val t1 = 80f; val t2 = 20f; val d = 0.05f
        val materials = listOf(
            HeatFlux.Conductivity.COPPER,
            HeatFlux.Conductivity.ALUMINUM,
            HeatFlux.Conductivity.STEEL,
            HeatFlux.Conductivity.GLASS,
            HeatFlux.Conductivity.WATER,
            HeatFlux.Conductivity.WOOD,
            HeatFlux.Conductivity.AIR
        )
        for (k in materials) {
            val flux = HeatFlux.compute(t1, t2, d, k)
            assertFalse("Expected nonzero flux for k=$k", flux == 0f)
        }
    }
}
