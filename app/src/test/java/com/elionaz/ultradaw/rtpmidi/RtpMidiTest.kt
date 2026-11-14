package com.elionaz.ultradaw.rtpmidi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtpMidiTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test fun `invitation round trip`() {
        val pkt = AppleMidiPacket.Exchange(AppleMidiPacket.Command.IN, 0x11223344, 0x55667788, "Mac Studio")
        val enc = AppleMidiPackets.encode(pkt)
        assertEquals("FF FF 49 4E 00 00 00 02 11 22 33 44 55 66 77 88", hex(enc.copyOfRange(0, 16)))
        assertEquals(0, enc.last().toInt())
        assertEquals(pkt, AppleMidiPackets.decode(enc))
    }

    @Test fun `bye has no name`() {
        val enc = AppleMidiPackets.encode(AppleMidiPacket.Exchange(AppleMidiPacket.Command.BY, 0, 7, "ignored"))
        assertEquals(16, enc.size)
    }

    @Test fun `clock sync round trip`() {
        val ck = AppleMidiPacket.ClockSync(42, 1, 100L, 200L, 0L)
        val enc = AppleMidiPackets.encode(ck)
        assertEquals(36, enc.size)
        assertEquals(ck, AppleMidiPackets.decode(enc))
    }

    @Test fun `non applemidi is ignored`() {
        assertNull(AppleMidiPackets.decode(bytes(0x80, 0x61, 0, 1)))
    }

    @Test fun `encode short packet`() {
        val enc = RtpMidiCodec.encode(1, 0x01020304, 0x0A0B0C0D, listOf(bytes(0x90, 0x5E, 0x7F)))
        assertEquals("80 61 00 01 01 02 03 04 0A 0B 0C 0D 03 90 5E 7F", hex(enc))
    }

    @Test fun `encode multiple messages adds delta times`() {
        val enc = RtpMidiCodec.encode(2, 0, 1, listOf(bytes(0x90, 0x5E, 0x7F), bytes(0x90, 0x5E, 0x00)))
        // length 7 = 3 + delta(1) + 3
        assertEquals("07 90 5E 7F 00 90 5E 00", hex(enc.copyOfRange(12, enc.size)))
    }

    @Test fun `encode long header for big payload`() {
        val sysex = ByteArray(40) { 0x20 }.also { it[0] = 0xF0.toByte(); it[39] = 0xF7.toByte() }
        val enc = RtpMidiCodec.encode(3, 0, 1, listOf(sysex))
        assertEquals(0x80, enc[12].toInt() and 0xF0)
        assertEquals(40, ((enc[12].toInt() and 0x0F) shl 8) or (enc[13].toInt() and 0xFF))
        val dec = RtpMidiCodec.decode(enc)!!
        assertArrayEquals(sysex, dec.messages.single())
    }

    @Test fun `decode round trip`() {
        val msgs = listOf(bytes(0xB0, 0x40, 0x31), bytes(0xE0, 0x7F, 0x7F), bytes(0xD0, 0x2C))
        val dec = RtpMidiCodec.decode(RtpMidiCodec.encode(9, 1234, 77, msgs))
        assertNotNull(dec)
        assertEquals(9, dec!!.sequence)
        assertEquals(1234L, dec.timestamp)
        assertEquals(77, dec.ssrc)
        assertEquals(msgs.map(::hex), dec.messages.map(::hex))
    }

    @Test fun `decode running status and delta times`() {
        // header Z=0, len 8: 90 5E 7F | delta 00 | 5D 7F (running) | delta 81 00 (2 bytes)| ... keep within len
        val payload = bytes(0x08, 0x90, 0x5E, 0x7F, 0x00, 0x5D, 0x7F, 0x00, 0xF8)
        val pkt = bytes(0x80, 0x61, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1) + payload
        val dec = RtpMidiCodec.decode(pkt)!!
        assertEquals(listOf("90 5E 7F", "90 5D 7F", "F8"), dec.messages.map(::hex))
    }

    @Test fun `decode ignores journal after command list`() {
        // J flag set (0x40), len 3, then junk journal bytes
        val pkt = bytes(0x80, 0x61, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0x43, 0x90, 0x10, 0x7F, 0x99, 0x01, 0x02)
        assertEquals(listOf("90 10 7F"), RtpMidiCodec.decode(pkt)!!.messages.map(::hex))
    }

    @Test fun `segmented sysex is reassembled`() {
        val asm = SysexAssembler()
        val p1 = bytes(0x80, 0x61, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0x04, 0xF0, 0x00, 0x00, 0xF0)
        val p2 = bytes(0x80, 0x61, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0x04, 0xF7, 0x66, 0x14, 0xF7)
        assertEquals(0, RtpMidiCodec.decode(p1, sysex = asm)!!.messages.size)
        assertEquals(listOf("F0 00 00 66 14 F7"), RtpMidiCodec.decode(p2, sysex = asm)!!.messages.map(::hex))
    }
}
