package com.example.connectapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RrdEventLogParserTest {

    // Пример с реальной платы (HyperTerminal): вывод `log dump`.
    private val sample = """
        Exiting monitoring...
        > log dump

        === RRD Event Log ===
        Idx | Date/Time         | Event   | S | Cur | Min | Max | Unit | Dur
        ----+-------------------+---------+---+-----+-----+-----+------+-----
        0   | 01.06.26 14:32:10 | A1 Stat | S | 28  | 28  | 28  | LSB  | 0
        1   | 01.06.26 14:32:11 | A1 Stat | E | 28  | 28  | 28  | LSB  | 1
        Total entries: 2
        > >
    """.trimIndent()

    @Test
    fun `parses RRD event log`() {
        val dump = RrdEventLogParser.parse(sample)
        assertNotNull(dump)
        assertEquals(2, dump!!.totalEntries)
        assertEquals(2, dump.events.size)

        val e0 = dump.events[0]
        assertEquals(0, e0.idx)
        assertEquals("01.06.26 14:32:10", e0.dateTime)
        assertEquals("A1 Stat", e0.event)
        assertEquals("S", e0.marker)
        assertEquals("28", e0.cur)
        assertEquals("28", e0.min)
        assertEquals("28", e0.max)
        assertEquals("LSB", e0.unit)
        assertEquals("0", e0.dur)

        assertEquals("E", dump.events[1].marker)
        assertEquals("1", dump.events[1].dur)
    }

    @Test
    fun `returns null without RRD block`() {
        assertNull(RrdEventLogParser.parse("monitor\n1234;25.0;25.0;0,0,0;0,0,0;0.00;0.00;"))
    }

    @Test
    fun `strips ansi codes before parsing`() {
        val withAnsi =
            "\u001B[2J=== RRD Event Log ===\n" +
            "Idx | Date/Time | Event | S | Cur | Min | Max | Unit | Dur\n" +
            "0 | t0 | E1 | S | 1 | 1 | 1 | LSB | 0\u001B[K\n" +
            "Total entries: 1"
        val dump = RrdEventLogParser.parse(withAnsi)
        assertNotNull(dump)
        assertEquals(1, dump!!.events.size)
        assertEquals("E1", dump.events[0].event)
        assertEquals("0", dump.events[0].dur)
    }
}
