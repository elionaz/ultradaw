package com.elionaz.ultradaw.rtpmidi

import java.nio.ByteBuffer

/**
 * AppleMIDI session-control packets (the "exchange" protocol used by macOS Network MIDI).
 * All of them start with 0xFFFF followed by a two-letter command, big-endian fields.
 */
sealed class AppleMidiPacket {
    enum class Command(val code: String) { IN("IN"), OK("OK"), NO("NO"), BY("BY") }

    /** IN = invitation, OK = accepted, NO = rejected, BY = end session. */
    data class Exchange(
        val command: Command,
        val token: Int,
        val ssrc: Int,
        val name: String? = null,
        val version: Int = PROTOCOL_VERSION,
    ) : AppleMidiPacket()

    /** Clock synchronisation. count 0 → 1 → 2; timestamps are in 100 µs units. */
    data class ClockSync(val ssrc: Int, val count: Int, val ts1: Long, val ts2: Long, val ts3: Long) : AppleMidiPacket()

    /** Receiver feedback: highest RTP sequence number received. */
    data class ReceiverFeedback(val ssrc: Int, val sequence: Int) : AppleMidiPacket()

    companion object {
        const val PROTOCOL_VERSION = 2
    }
}

object AppleMidiPackets {

    fun isAppleMidi(data: ByteArray, length: Int = data.size): Boolean =
        length >= 4 && data[0] == 0xFF.toByte() && data[1] == 0xFF.toByte()

    fun decode(data: ByteArray, length: Int = data.size): AppleMidiPacket? {
        if (!isAppleMidi(data, length)) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        buf.position(2)
        val cmd = String(byteArrayOf(buf.get(), buf.get()), Charsets.US_ASCII)
        return try {
            when (cmd) {
                "IN", "OK", "NO", "BY" -> {
                    val command = AppleMidiPacket.Command.valueOf(cmd)
                    val version = buf.int
                    val token = buf.int
                    val ssrc = buf.int
                    val name = if (buf.hasRemaining()) {
                        val start = buf.position()
                        var end = start
                        while (end < length && data[end] != 0.toByte()) end++
                        String(data, start, end - start, Charsets.UTF_8)
                    } else null
                    AppleMidiPacket.Exchange(command, token, ssrc, name, version)
                }
                "CK" -> {
                    val ssrc = buf.int
                    val count = buf.get().toInt() and 0xFF
                    buf.position(buf.position() + 3)
                    AppleMidiPacket.ClockSync(ssrc, count, buf.long, buf.long, buf.long)
                }
                "RS" -> {
                    val ssrc = buf.int
                    val seq = buf.int ushr 16
                    AppleMidiPacket.ReceiverFeedback(ssrc, seq)
                }
                else -> null
            }
        } catch (_: java.nio.BufferUnderflowException) {
            null
        }
    }

    fun encode(packet: AppleMidiPacket): ByteArray = when (packet) {
        is AppleMidiPacket.Exchange -> {
            val nameBytes = packet.name?.toByteArray(Charsets.UTF_8)
            val includeName = packet.command != AppleMidiPacket.Command.BY && nameBytes != null
            val size = 16 + if (includeName) nameBytes!!.size + 1 else 0
            ByteBuffer.allocate(size).apply {
                putShort(0xFFFF.toShort())
                put(packet.command.code.toByteArray(Charsets.US_ASCII))
                putInt(packet.version)
                putInt(packet.token)
                putInt(packet.ssrc)
                if (includeName) {
                    put(nameBytes!!)
                    put(0)
                }
            }.array()
        }
        is AppleMidiPacket.ClockSync -> ByteBuffer.allocate(36).apply {
            putShort(0xFFFF.toShort())
            put("CK".toByteArray(Charsets.US_ASCII))
            putInt(packet.ssrc)
            put(packet.count.toByte())
            put(ByteArray(3))
            putLong(packet.ts1)
            putLong(packet.ts2)
            putLong(packet.ts3)
        }.array()
        is AppleMidiPacket.ReceiverFeedback -> ByteBuffer.allocate(12).apply {
            putShort(0xFFFF.toShort())
            put("RS".toByteArray(Charsets.US_ASCII))
            putInt(packet.ssrc)
            putInt(packet.sequence shl 16)
        }.array()
    }
}
