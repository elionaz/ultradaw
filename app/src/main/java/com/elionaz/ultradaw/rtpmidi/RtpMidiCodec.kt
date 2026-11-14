package com.elionaz.ultradaw.rtpmidi

import java.io.ByteArrayOutputStream

data class RtpMidiPacket(
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Int,
    /** Complete MIDI messages (status byte included, running status expanded). */
    val messages: List<ByteArray>,
)

/**
 * RTP-MIDI payload codec (RFC 6295), reduced to what a LAN control surface needs:
 * - decodes the MIDI command section (short/long header, delta times, running status,
 *   complete and segmented SysEx), ignoring the recovery journal;
 * - encodes packets without a journal (J=0).
 */
object RtpMidiCodec {
    const val PAYLOAD_TYPE = 0x61
    private const val RTP_HEADER = 12

    fun decode(data: ByteArray, length: Int = data.size, sysex: SysexAssembler? = null): RtpMidiPacket? {
        if (length < RTP_HEADER + 1) return null
        val b0 = data[0].toInt() and 0xFF
        if (b0 ushr 6 != 2) return null // RTP version 2
        val pt = data[1].toInt() and 0x7F
        if (pt != PAYLOAD_TYPE) return null
        val seq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val ts = readU32(data, 4)
        val ssrc = readU32(data, 8).toInt()

        var i = RTP_HEADER
        val h = data[i].toInt() and 0xFF
        val longHeader = h and 0x80 != 0
        val zFlag = h and 0x20 != 0
        val len: Int
        if (longHeader) {
            if (length < i + 2) return null
            len = ((h and 0x0F) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        } else {
            len = h and 0x0F
            i += 1
        }
        val end = minOf(i + len, length)
        val out = mutableListOf<ByteArray>()
        var running = 0
        var first = true
        val assembler = sysex ?: SysexAssembler()

        while (i < end) {
            if (!first || zFlag) {
                var n = 0
                while (i < end && n < 4) {
                    val d = data[i++].toInt() and 0xFF
                    n++
                    if (d and 0x80 == 0) break
                }
                if (i >= end) break
            }
            first = false

            var status = data[i].toInt() and 0xFF
            if (status and 0x80 != 0) {
                i++
            } else {
                if (running == 0) break
                status = running
            }

            when {
                status == 0xF0 || status == 0xF7 -> {
                    // SysEx segment: bytes until a terminating 0xF7 (end) or 0xF0 (continues).
                    val seg = ByteArrayOutputStream()
                    var term = -1
                    while (i < end) {
                        val d = data[i++].toInt() and 0xFF
                        if (d == 0xF7 || d == 0xF0) { term = d; break }
                        seg.write(d)
                    }
                    assembler.segment(status, seg.toByteArray(), term)?.let(out::add)
                    running = 0
                }
                status >= 0xF8 -> out.add(byteArrayOf(status.toByte()))
                status in 0xF1..0xF6 -> {
                    val size = when (status) { 0xF2 -> 2; 0xF1, 0xF3 -> 1; else -> 0 }
                    if (i + size > end) break
                    out.add(byteArrayOf(status.toByte()) + data.copyOfRange(i, i + size))
                    i += size
                    running = 0
                }
                else -> {
                    val size = if ((status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0) 1 else 2
                    if (i + size > end) break
                    out.add(byteArrayOf(status.toByte()) + data.copyOfRange(i, i + size))
                    i += size
                    running = status
                }
            }
        }
        return RtpMidiPacket(seq, ts, ssrc, out)
    }

    fun encode(sequence: Int, timestamp: Long, ssrc: Int, messages: List<ByteArray>): ByteArray {
        val list = ByteArrayOutputStream()
        messages.forEachIndexed { idx, m ->
            if (idx > 0) list.write(0x00) // delta time 0 before every command but the first (Z=0)
            list.write(m)
        }
        val body = list.toByteArray()
        require(body.size <= 0x0FFF) { "MIDI list too long: ${body.size}" }
        val out = ByteArrayOutputStream(RTP_HEADER + 2 + body.size)
        out.write(0x80)
        out.write(PAYLOAD_TYPE)
        out.write((sequence ushr 8) and 0xFF)
        out.write(sequence and 0xFF)
        writeU32(out, timestamp)
        writeU32(out, ssrc.toLong())
        if (body.size <= 15) {
            out.write(body.size)
        } else {
            out.write(0x80 or ((body.size ushr 8) and 0x0F))
            out.write(body.size and 0xFF)
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun readU32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }
}

/**
 * Reassembles RTP-MIDI SysEx segments (RFC 6295 §3.2):
 *  F0 … F7 complete · F0 … F0 first · F7 … F0 middle · F7 … F7 last.
 * Emits complete messages framed as F0 … F7.
 */
class SysexAssembler {
    private var buffer: ByteArrayOutputStream? = null

    fun segment(start: Int, body: ByteArray, terminator: Int): ByteArray? {
        if (start == 0xF0) {
            if (terminator == 0xF7) {
                buffer = null
                return byteArrayOf(0xF0.toByte()) + body + byteArrayOf(0xF7.toByte())
            }
            if (terminator == 0xF0) {
                buffer = ByteArrayOutputStream().apply { write(0xF0); write(body) }
            }
            return null
        }
        // start == 0xF7: continuation of a segmented SysEx
        val buf = buffer ?: return null
        buf.write(body)
        return when (terminator) {
            0xF0 -> null
            0xF7 -> { buf.write(0xF7); buffer = null; buf.toByteArray() }
            else -> { buffer = null; null }
        }
    }
}
