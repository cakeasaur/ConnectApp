package com.example.connectapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamDecoderTest {

    @Test
    fun `two-byte UTF-8 character split across chunks decodes correctly`() {
        val decoder = LineDecoder()
        // "Й\n" in UTF-8: 0xD0 0x99 0x0A — split after first byte
        val full = "Й\n".toByteArray(Charsets.UTF_8)
        val chunk1 = full.copyOfRange(0, 1)
        val chunk2 = full.copyOfRange(1, full.size)

        val lines1 = decoder.feed(chunk1, chunk1.size)
        assertEquals(0, lines1.size)

        val lines2 = decoder.feed(chunk2, chunk2.size)
        assertEquals(1, lines2.size)
        assertEquals("Й", lines2[0])
    }

    @Test
    fun `four-byte UTF-8 emoji split across three chunks decodes correctly`() {
        val decoder = LineDecoder()
        // U+1F600 😀 is 4 bytes: F0 9F 98 80
        val full = "😀\n".toByteArray(Charsets.UTF_8)
        assertEquals(5, full.size)

        val lines1 = decoder.feed(full, 2)
        assertEquals(0, lines1.size)

        val lines2 = decoder.feed(full, 2, 2)
        assertEquals(0, lines2.size)

        val lines3 = decoder.feed(full, 4, 1)
        assertEquals(1, lines3.size)
        assertEquals("😀", lines3[0])
    }

    @Test
    fun `cyrillic word split mid-character across chunks`() {
        val decoder = LineDecoder()
        val full = "Привет\n".toByteArray(Charsets.UTF_8)
        // "П" = D0 9F, split after first byte of "П"
        val chunk1 = full.copyOfRange(0, 1)
        val chunk2 = full.copyOfRange(1, full.size)

        assertEquals(0, decoder.feed(chunk1, chunk1.size).size)
        val lines = decoder.feed(chunk2, chunk2.size)
        assertEquals(1, lines.size)
        assertEquals("Привет", lines[0])
    }

    @Test
    fun `multiple complete lines in single chunk`() {
        val decoder = LineDecoder()
        val bytes = "line1\nline2\nline3\n".toByteArray(Charsets.UTF_8)
        val lines = decoder.feed(bytes, bytes.size)
        assertEquals(listOf("line1", "line2", "line3"), lines)
    }

    @Test
    fun `partial last line held until flush`() {
        val decoder = LineDecoder()
        val bytes = "line1\npartial".toByteArray(Charsets.UTF_8)
        val lines = decoder.feed(bytes, bytes.size)
        assertEquals(listOf("line1"), lines)
        assertEquals("partial", decoder.flush())
    }

    @Test
    fun `tail without newline emitted on flush`() {
        val decoder = LineDecoder()
        val bytes = "Привет".toByteArray(Charsets.UTF_8)
        val lines = decoder.feed(bytes, bytes.size)
        assertEquals(0, lines.size)
        assertEquals("Привет", decoder.flush())
    }

    @Test
    fun `empty accumulator flush returns null`() {
        val decoder = LineDecoder()
        assertNull(decoder.flush())
    }

    @Test
    fun `crlf line stripped of cr`() {
        val decoder = LineDecoder()
        val bytes = "data\r\n".toByteArray(Charsets.UTF_8)
        val lines = decoder.feed(bytes, bytes.size)
        assertEquals(1, lines.size)
        assertEquals("data\r", lines[0])
    }
}

private fun LineDecoder.feed(bytes: ByteArray, offset: Int, count: Int): List<String> {
    return feed(bytes.copyOfRange(offset, offset + count), count)
}
