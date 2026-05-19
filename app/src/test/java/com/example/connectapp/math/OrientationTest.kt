package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test

class OrientationTest {

    @Test
    fun `flat orientation gives zero pitch and roll`() {
        val pitch = Orientation.pitchDeg(0f, 0f, 9.81f)
        val roll = Orientation.rollDeg(0f, 0f, 9.81f)
        assertEquals(0f, pitch, 0.01f)
        assertEquals(0f, roll, 0.01f)
    }

    @Test
    fun `ax equals g gives pitch near minus 90 degrees`() {
        val pitch = Orientation.pitchDeg(9.81f, 0f, 0f)
        assertEquals(-90f, pitch, 0.1f)
    }

    @Test
    fun `magnitude of unit vector along x is 1`() {
        assertEquals(1f, Orientation.magnitude(1f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `magnitude of 3d vector is correct`() {
        // (3,4,0) -> magnitude 5
        assertEquals(5f, Orientation.magnitude(3f, 4f, 0f), 1e-5f)
    }

    @Test
    fun `roll is 90 degrees when ay equals g and az equals zero`() {
        val roll = Orientation.rollDeg(0f, 9.81f, 0f)
        assertEquals(90f, roll, 0.1f)
    }
}
