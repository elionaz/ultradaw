package com.elionaz.ultradaw.mcu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McuTest {
    private val parser = McuParser()
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    private fun apply(vararg msgs: ByteArray): McuParser.Result {
        var r = McuParser.Result(SurfaceState())
        val replies = mutableListOf<ByteArray>()
        for (m in msgs) { r = parser.handle(r.state, m); replies += r.replies }
        return McuParser.Result(r.state, replies)
    }

    @Test fun `device query gets host connection query with serial and challenge`() {
        val r = apply(bytes(0xF0, 0x00, 0x00, 0x66, 0x14, 0x00, 0xF7))
        assertEquals(
            "F0 00 00 66 14 01 55 44 41 57 30 30 31 01 02 03 04 F7",
            hex(r.replies.single()),
        )
    }

    @Test fun `host reply is confirmed and marks online`() {
        val r = apply(bytes(0xF0, 0x00, 0x00, 0x66, 0x14, 0x02, 0x55, 0x44, 0x41, 0x57, 0x30, 0x30, 0x31, 1, 2, 3, 4, 0xF7))
        assertTrue(r.state.hostOnline)
        assertEquals("F0 00 00 66 14 03 55 44 41 57 30 30 31 F7", hex(r.replies.single()))
    }

    @Test fun `lcd writes track names and values`() {
        val name = "Vocals ".toByteArray()
        val lcd = bytes(0xF0, 0x00, 0x00, 0x66, 0x14, 0x12, 7) + name + bytes(0xF7)
        val value = bytes(0xF0, 0x00, 0x00, 0x66, 0x14, 0x12, 56 + 7) + "  -6.0 ".toByteArray() + bytes(0xF7)
        val s = apply(lcd, value).state
        assertEquals("Vocals", s.stripTop(1))
        assertEquals("-6.0", s.stripBottom(1))
        assertEquals("", s.stripTop(0))
    }

    @Test fun `timecode digits fill from the right with dots`() {
        // "  1 1 1   1" style: CC 0x49 is leftmost; 0x40 rightmost
        val s = apply(
            bytes(0xB0, 0x49, 0x31), // leftmost '1'
            bytes(0xB0, 0x40, 0x39), // rightmost '9'
            bytes(0xB0, 0x47, 0x32 or 0x40), // index 2 = '2' with dot
        ).state
        assertEquals('1', s.timecode[0])
        assertEquals('2', s.timecode[2])
        assertTrue(s.timecodeDots[2])
        assertEquals('9', s.timecode[9])
    }

    @Test fun `seven segment letters map to ascii`() {
        assertEquals('A', McuParser.segChar(0x01))
        assertEquals(' ', McuParser.segChar(0x20))
        assertEquals('5', McuParser.segChar(0x35 or 0x40))
    }

    @Test fun `meters and clip`() {
        val s = apply(bytes(0xD0, 0x2C), bytes(0xD0, 0x2E)).state
        assertEquals(12, s.meters[2])
        assertTrue(s.clips[2])
        assertFalse(apply(bytes(0xD0, 0x2E), bytes(0xD0, 0x2F)).state.clips[2])
    }

    @Test fun `faders from pitch bend including master`() {
        val s = apply(bytes(0xE3, 0x7F, 0x7F), bytes(0xE8, 0x00, 0x40)).state
        assertEquals(0x3FFF, s.faders[3])
        assertEquals(0x2000, s.faders[Mcu.MASTER])
    }

    @Test fun `leds on blink off`() {
        val s1 = apply(bytes(0x90, Mcu.PLAY, 0x7F), bytes(0x90, Mcu.RECORD, 0x01)).state
        assertEquals(Led.ON, s1.led(Mcu.PLAY))
        assertEquals(Led.BLINK, s1.led(Mcu.RECORD))
        val s2 = parser.handle(s1, bytes(0x90, Mcu.PLAY, 0x00)).state
        assertEquals(Led.OFF, s2.led(Mcu.PLAY))
    }

    @Test fun `version reply starts with V`() {
        val r = apply(bytes(0xF0, 0x00, 0x00, 0x66, 0x14, 0x13, 0x00, 0xF7))
        assertEquals("F0 00 00 66 14 14 56 31 2E 30 30 F7", hex(r.replies.single()))
    }

    @Test fun `encoder messages`() {
        assertEquals("90 5E 7F", hex(McuEncoder.button(Mcu.PLAY, true)))
        assertEquals(listOf("90 5D 7F", "90 5D 00"), McuEncoder.tap(Mcu.STOP).map(::hex))
        assertEquals("90 6A 7F", hex(McuEncoder.faderTouch(2, true)))
        assertEquals("E2 7F 7F", hex(McuEncoder.faderMove(2, 0x3FFF)))
        assertEquals("E8 00 40", hex(McuEncoder.faderMove(Mcu.MASTER, 0x2000)))
        assertEquals("E0 7F 7F", hex(McuEncoder.faderMove(0, 99999)))
    }
}
