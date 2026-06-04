package com.example.connectapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HelpCommandsTest {

    @Before
    fun reset() {
        HelpCommands.clear()
    }

    @Test
    fun `parses commands from help block`() {
        listOf(
            "Available commands:",
            "  monitor   - start telemetry stream",
            "  calib     - load accelerometer calibration",
            "  test      - run self-test",
            "  log dump  - dump RRD event log",
        ).forEach { HelpCommands.feed(it) }

        val cmds = HelpCommands.commands.value
        assertTrue("monitor" in cmds)
        assertTrue("calib" in cmds)
        assertTrue("test" in cmds)
        assertTrue("log dump" in cmds)
    }

    @Test
    fun `strips angle-bracket args from command name`() {
        HelpCommands.feed("Commands:")
        HelpCommands.feed("set cpu clock <value> - change CPU speed")
        assertTrue("set cpu clock" in HelpCommands.commands.value)
    }

    @Test
    fun `ignores plain telemetry and prose without dash`() {
        HelpCommands.feed("Available commands:")
        HelpCommands.feed("11329;22.0;22.0;0,0,0;0,0,0;")
        HelpCommands.feed("Calibration data successfully loaded")
        assertEquals(0, HelpCommands.commands.value.size)
    }

    @Test
    fun `does not capture outside help block`() {
        // Без строки-заголовка захват не активен.
        HelpCommands.feed("reboot - restart device")
        assertEquals(0, HelpCommands.commands.value.size)
    }

    @Test
    fun `clear empties commands`() {
        HelpCommands.feed("Commands:")
        HelpCommands.feed("ping - test link")
        assertTrue(HelpCommands.commands.value.isNotEmpty())
        HelpCommands.clear()
        assertEquals(0, HelpCommands.commands.value.size)
    }
}
