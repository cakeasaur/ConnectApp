package com.example.connectapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DataParserTest {

    @Test
    fun `verbose temperature line is parsed`() {
        val r = DataParser.parse("Temperature on I2C1: 28.5 C")
        assertNotNull(r)
        assertEquals(28.5f, r!!.temperature)
    }

    @Test
    fun `compact T-colon format is parsed`() {
        val r = DataParser.parse("T:23.50")
        assertEquals(23.5f, r?.temperature)
    }

    @Test
    fun `labeled X Y Z are parsed`() {
        val r = DataParser.parse("X: 254 Y: 0 Z: 59")
        assertEquals(254f, r?.accelX)
        assertEquals(0f, r?.accelY)
        assertEquals(59f, r?.accelZ)
    }

    @Test
    fun `axis labels with AX prefix work`() {
        val r = DataParser.parse("AX: 12 AY: 34 AZ: -5")
        assertEquals(12f, r?.accelX)
        assertEquals(34f, r?.accelY)
        assertEquals(-5f, r?.accelZ)
    }

    @Test
    fun `firmware monitor CSV line picks first sensor pair`() {
        val r = DataParser.parse("567;29.5;29.5;0,0,0;0,0,1;0.00;0.01;")
        assertNotNull(r)
        assertEquals(29.5f, r!!.temperature)
        assertEquals(0f, r.accelX)
        assertEquals(0f, r.accelY)
        assertEquals(0f, r.accelZ)
    }

    @Test
    fun `bare format temp X Y Z parses`() {
        val r = DataParser.parse("28.50 254 0 59")
        assertEquals(28.5f, r?.temperature)
        assertEquals(254f, r?.accelX)
        assertEquals(0f, r?.accelY)
        assertEquals(59f, r?.accelZ)
    }

    @Test
    fun `calibration line returns null`() {
        // Калибровочные строки не должны давать ложно-нулевую температуру.
        val r = DataParser.parse("Calibration - Temp: 0.0 to 0.0 C")
        assertNull(r)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(DataParser.parse(""))
        assertNull(DataParser.parse("hello world"))
        assertNull(DataParser.parse("???"))
    }

    @Test
    fun `negative temperature is parsed`() {
        val r = DataParser.parse("Temp: -12.5 C")
        assertEquals(-12.5f, r?.temperature)
    }

    @Test
    fun `i2c integer suffix is not picked as temperature`() {
        // "I2C1: 28.5" — раньше regex без обязательной точки мог ловить '1' из I2C1.
        // Сейчас обязательна точка ⇒ температура = 28.5, не 1.
        val r = DataParser.parse("Temperature on I2C1: 28.5 C")
        assertEquals(28.5f, r?.temperature)
    }
}
