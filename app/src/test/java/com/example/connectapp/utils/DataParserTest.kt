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
        assertEquals(28.5f, r!!.temperature1)
    }

    @Test
    fun `compact T-colon format is parsed`() {
        val r = DataParser.parse("T:23.50")
        assertEquals(23.5f, r?.temperature1)
    }

    @Test
    fun `labeled X Y Z are parsed into accel1`() {
        val r = DataParser.parse("X: 254 Y: 0 Z: 59")
        assertEquals(254f, r?.accel1X)
        assertEquals(0f, r?.accel1Y)
        assertEquals(59f, r?.accel1Z)
    }

    @Test
    fun `axis labels with AX prefix work`() {
        val r = DataParser.parse("AX: 12 AY: 34 AZ: -5")
        assertEquals(12f, r?.accel1X)
        assertEquals(34f, r?.accel1Y)
        assertEquals(-5f, r?.accel1Z)
    }

    @Test
    fun `firmware monitor CSV picks both sensors`() {
        val r = DataParser.parse("567;29.5;30.7;0,0,0;1,2,3;0.00;0.01;")
        assertNotNull(r)
        assertEquals(29.5f, r!!.temperature1)
        assertEquals(30.7f, r.temperature2)
        assertEquals(0f, r.accel1X)
        assertEquals(0f, r.accel1Y)
        assertEquals(0f, r.accel1Z)
        assertEquals(1f, r.accel2X)
        assertEquals(2f, r.accel2Y)
        assertEquals(3f, r.accel2Z)
    }

    @Test
    fun `bare 4 values parses as T1 plus accel1`() {
        val r = DataParser.parse("28.50 254 0 59")
        assertEquals(28.5f, r?.temperature1)
        assertEquals(254f, r?.accel1X)
        assertEquals(0f, r?.accel1Y)
        assertEquals(59f, r?.accel1Z)
        assertNull(r?.temperature2)
    }

    @Test
    fun `bare 8 values parses both sensors`() {
        val r = DataParser.parse("28.5 29.1 0 0 0 1 2 3")
        assertEquals(28.5f, r?.temperature1)
        assertEquals(29.1f, r?.temperature2)
        assertEquals(0f, r?.accel1X)
        assertEquals(0f, r?.accel1Y)
        assertEquals(0f, r?.accel1Z)
        assertEquals(1f, r?.accel2X)
        assertEquals(2f, r?.accel2Y)
        assertEquals(3f, r?.accel2Z)
    }

    @Test
    fun `calibration line returns null`() {
        assertNull(DataParser.parse("Calibration - Temp: 0.0 to 0.0 C"))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(DataParser.parse(""))
        assertNull(DataParser.parse("hello world"))
        assertNull(DataParser.parse("???"))
    }

    @Test
    fun `temperature only line has null accel fields`() {
        val r = DataParser.parse("Temp: 25.0 C")
        assertNotNull(r)
        assertEquals(25.0f, r!!.temperature1)
        assertNull(r.accel1X)
        assertNull(r.accel1Y)
        assertNull(r.accel1Z)
    }

    @Test
    fun `compact T-equals format is parsed`() {
        val r = DataParser.parse("T=28.5")
        assertNotNull(r)
        assertEquals(28.5f, r!!.temperature1)
    }

    @Test
    fun `negative accel values are parsed correctly`() {
        val r = DataParser.parse("X: -128 Y: 0 Z: 64")
        assertEquals(-128f, r?.accel1X)
        assertEquals(0f, r?.accel1Y)
        assertEquals(64f, r?.accel1Z)
    }

    @Test
    fun `crlf line ending does not break parsing`() {
        val r = DataParser.parse("T:23.5\r\n")
        assertNotNull(r)
        assertEquals(23.5f, r!!.temperature1)
    }

    @Test
    fun `firmware CSV with negative temperatures parses correctly`() {
        val r = DataParser.parse("1;-5.5;-3.2;0,0,0;0,0,0;0;0;")
        assertNotNull(r)
        assertEquals(-5.5f, r!!.temperature1)
        assertEquals(-3.2f, r.temperature2)
        assertEquals(0f, r.accel1X)
        assertEquals(0f, r.accel2Z)
    }

    @Test
    fun `bare 4 values with comma separator parses correctly`() {
        val r = DataParser.parse("28.50,254,0,59")
        assertNotNull(r)
        assertEquals(28.5f, r!!.temperature1)
        assertEquals(254f, r.accel1X)
        assertEquals(0f, r.accel1Y)
        assertEquals(59f, r.accel1Z)
    }

    @Test
    fun `negative temperature is parsed`() {
        val r = DataParser.parse("Temp: -12.5 C")
        assertEquals(-12.5f, r?.temperature1)
    }

    @Test
    fun `i2c integer suffix is not picked as temperature`() {
        val r = DataParser.parse("Temperature on I2C1: 28.5 C")
        assertEquals(28.5f, r?.temperature1)
    }

    @Test
    fun `bare format does not lose first digit`() {
        // Регрессия — раньше greedy '^[^a-zA-Z]*' сжирал '2', и температура была 8.50.
        val r = DataParser.parse("28.50 254 0 59")
        assertEquals(28.5f, r?.temperature1)
    }

    @Test
    fun `firmware CSV with trailing fields still parses`() {
        // На реальной плате после 'ax2,ay2,az2;' могут быть CRC/timestamp/прочее.
        val r = DataParser.parse("100;25.0;26.0;1,2,3;4,5,6;0.00;0.01;0xAB;")
        assertEquals(25.0f, r?.temperature1)
        assertEquals(26.0f, r?.temperature2)
        assertEquals(4f, r?.accel2X)
    }

    @Test
    fun `csv with fractional accel values parses`() {
        // Плата шлёт ускорение в g (дробные) — раньше regex требовал целые и падал.
        val r = DataParser.parse("12;25.0;26.0;0.01,-0.02,1.00;0.03,0.04,0.98;")
        assertNotNull(r)
        assertEquals(25.0f, r!!.temperature1)
        assertEquals(0.01f, r.accel1X)
        assertEquals(-0.02f, r.accel1Y)
        assertEquals(1.00f, r.accel1Z)
        assertEquals(0.98f, r.accel2Z)
    }

    @Test
    fun `csv with integer temperatures parses`() {
        val r = DataParser.parse("7;29;30;0,0,0;1,2,3;")
        assertEquals(29f, r?.temperature1)
        assertEquals(30f, r?.temperature2)
        assertEquals(1f, r?.accel2X)
    }

    @Test
    fun `csv without leading counter parses`() {
        val r = DataParser.parse("25.5;26.5;0,0,0;1,2,3")
        assertEquals(25.5f, r?.temperature1)
        assertEquals(26.5f, r?.temperature2)
        assertEquals(3f, r?.accel2Z)
    }

    @Test
    fun `accel-only triple parses into accel1`() {
        val r = DataParser.parse("254,0,59")
        assertNotNull(r)
        assertEquals(254f, r!!.accel1X)
        assertEquals(59f, r.accel1Z)
        assertNull(r.temperature1)
    }

    @Test
    fun `six values parse as two accelerometers`() {
        val r = DataParser.parse("1 2 3 4 5 6")
        assertEquals(1f, r?.accel1X)
        assertEquals(3f, r?.accel1Z)
        assertEquals(4f, r?.accel2X)
        assertEquals(6f, r?.accel2Z)
        assertNull(r?.temperature1)
    }

    @Test
    fun `tab separated eight values parse`() {
        val r = DataParser.parse("28.5\t29.1\t0\t0\t0\t1\t2\t3")
        assertEquals(28.5f, r?.temperature1)
        assertEquals(3f, r?.accel2Z)
    }

    @Test
    fun `clock-like time string is not parsed as accel`() {
        assertNull(DataParser.parse("12:34:56"))
    }

    @Test
    fun `drainRecords frames newline-less semicolon stream by counter`() {
        // Реальный формат платы: записи без '\n', разделены только ';'.
        val sb = StringBuilder("11329;22.0;22.0;0,0,0;0,0,0;0.00;0.00;")
        // Первая запись ещё не завершена — следующего счётчика нет.
        assertEquals(0, DataParser.drainRecords(sb).size)
        // Приходит следующая запись → предыдущая становится полной.
        sb.append("11330;23.5;24.0;1,2,3;4,5,6;0.10;0.20;")
        val recs = DataParser.drainRecords(sb)
        assertEquals(1, recs.size)
        val r = DataParser.parse(recs[0])
        assertEquals(22.0f, r?.temperature1)
        assertEquals(22.0f, r?.temperature2)
        assertEquals(0f, r?.accel1X)
        assertEquals(0f, r?.accel2Z)
    }

    @Test
    fun `drainRecords caps buffer on long unframed text`() {
        // Текст без '\n' и ';' (вывод команд) не должен копиться бесконечно.
        val sb = StringBuilder()
        repeat(1000) { sb.append("status - Show system status   ") } // ~30k, нет '\n'/';'
        DataParser.drainRecords(sb)
        org.junit.Assert.assertTrue("буфер должен быть обрезан", sb.length < 1024)
    }

    @Test
    fun `drainRecords still splits on newlines`() {
        val sb = StringBuilder("28.5 29.1 0 0 0 1 2 3\nT:25.0\n")
        val recs = DataParser.drainRecords(sb)
        assertEquals(2, recs.size)
        assertEquals(28.5f, DataParser.parse(recs[0])?.temperature1)
        assertEquals(25.0f, DataParser.parse(recs[1])?.temperature1)
    }
}
