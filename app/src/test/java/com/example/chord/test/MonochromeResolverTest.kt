package com.example.chord.playback.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonochromeResolverTest {
    @Test
    fun `parses CD quality FLAC streaminfo`() {
        val bytes = byteArrayOf(
            0x66, 0x4c, 0x61, 0x43, 0x00, 0x00, 0x00, 0x22,
            0x04, 0x80.toByte(), 0x04, 0x80.toByte(), 0x00, 0x00, 0x0e, 0x00,
            0x11, 0x2d, 0x0a, 0xc4.toByte(), 0x42, 0xf0.toByte(), 0x00, 0x74,
            0xf3.toByte(), 0xbf.toByte(), 0xdc.toByte(), 0xee.toByte(),
            0xc0.toByte(), 0x89.toByte(), 0xf2.toByte(), 0x89.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val info = MonochromeResolver.parseFlacInfo(bytes)
        assertEquals(44_100, info?.rate)
        assertEquals(16, info?.bits)
        assertEquals("FLAC · 16-bit / 44.1 kHz", info?.label)
    }

    @Test
    fun `parses hi res FLAC streaminfo`() {
        val infoBytes = ByteArray(18).also {
            it[10] = 0x17
            it[11] = 0x70
            it[12] = 0x02
            it[13] = 0x70
        }
        val header = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 0x22) + infoBytes
        val info = MonochromeResolver.parseFlacInfo(header)
        assertEquals(96_000, info?.rate)
        assertEquals(24, info?.bits)
        assertEquals("Hi-Res FLAC · 24-bit / 96 kHz", info?.label)
    }

    @Test
    fun `rejects non FLAC content and truncated marker`() {
        assertNull(MonochromeResolver.parseFlacInfo("<html".toByteArray()))
        assertNull(MonochromeResolver.parseFlacInfo(byteArrayOf(0x66, 0x4c)))
    }
}
