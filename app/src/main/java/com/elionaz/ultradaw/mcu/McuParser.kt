package com.elionaz.ultradaw.mcu

/**
 * Applies host → surface MCU messages to [SurfaceState] and produces any replies the
 * protocol requires (handshake, version). Pure: no I/O, easy to unit-test.
 */
class McuParser(
    /** 7-byte ASCII serial reported to the host. */
    private val serial: ByteArray = "UDAW001".toByteArray(Charsets.US_ASCII),
    private val deviceId: Int = Mcu.DEVICE_MCU,
) {
    init { require(serial.size == 7) }

    data class Result(val state: SurfaceState, val replies: List<ByteArray> = emptyList())

    fun handle(state: SurfaceState, msg: ByteArray): Result {
        if (msg.isEmpty()) return Result(state)
        val status = msg[0].toInt() and 0xFF
        return when {
            status == 0xF0 -> handleSysex(state, msg)
            status and 0xF0 == 0x90 && msg.size >= 3 -> Result(setLed(state, u(msg[1]), ledFor(u(msg[2]))))
            status and 0xF0 == 0x80 && msg.size >= 3 -> Result(setLed(state, u(msg[1]), Led.OFF))
            status and 0xF0 == 0xB0 && msg.size >= 3 -> Result(handleCc(state, u(msg[1]), u(msg[2])))
            status and 0xF0 == 0xD0 && msg.size >= 2 -> Result(handleMeter(state, u(msg[1])))
            status and 0xF0 == 0xE0 && msg.size >= 3 -> {
                val ch = status and 0x0F
                if (ch > Mcu.MASTER) Result(state)
                else {
                    val v = u(msg[1]) or (u(msg[2]) shl 7)
                    Result(state.copy(faders = state.faders.replaced(ch, v), hostOnline = true))
                }
            }
            else -> Result(state)
        }
    }

    private fun handleSysex(state: SurfaceState, m: ByteArray): Result {
        // F0 00 00 66 dd cc … F7
        if (m.size < 7 || u(m[1]) != 0 || u(m[2]) != 0 || u(m[3]) != 0x66) return Result(state)
        val body = m.copyOfRange(6, if (u(m.last()) == 0xF7) m.size - 1 else m.size)
        return when (u(m[5])) {
            Mcu.SYSEX_DEVICE_QUERY -> Result(state, listOf(sysex(Mcu.SYSEX_HOST_QUERY, serial + CHALLENGE)))
            Mcu.SYSEX_HOST_REPLY -> Result(state.copy(hostOnline = true), listOf(sysex(Mcu.SYSEX_CONFIRM, serial)))
            Mcu.SYSEX_ERROR, Mcu.SYSEX_GO_OFFLINE -> Result(state.copy(hostOnline = false))
            Mcu.SYSEX_VERSION_REQUEST ->
                Result(state, listOf(sysex(Mcu.SYSEX_VERSION_REPLY, VERSION.toByteArray(Charsets.US_ASCII))))
            Mcu.SYSEX_LCD -> {
                if (body.isEmpty()) return Result(state)
                val offset = u(body[0])
                val chars = state.lcd.toCharArray()
                for (k in 1 until body.size) {
                    val pos = offset + k - 1
                    if (pos in chars.indices) chars[pos] = lcdChar(u(body[k]))
                }
                Result(state.copy(lcd = String(chars), hostOnline = true))
            }
            Mcu.SYSEX_FADERS_MIN -> Result(state.copy(faders = List(Mcu.STRIPS + 1) { 0 }))
            Mcu.SYSEX_LEDS_OFF -> Result(state.copy(leds = emptyMap()))
            Mcu.SYSEX_RESET -> Result(SurfaceState(hostOnline = state.hostOnline))
            else -> Result(state)
        }
    }

    private fun handleCc(state: SurfaceState, cc: Int, v: Int): SurfaceState = when (cc) {
        in Mcu.CC_TIMECODE_FIRST..Mcu.CC_TIMECODE_LAST -> {
            val idx = 9 - (cc - Mcu.CC_TIMECODE_FIRST)
            state.copy(
                timecode = state.timecode.replaced(idx, segChar(v)),
                timecodeDots = state.timecodeDots.replaced(idx, v and 0x40 != 0),
                hostOnline = true,
            )
        }
        Mcu.CC_ASSIGN_LEFT -> state.copy(assignment = "${segChar(v)}${state.assignment[1]}")
        Mcu.CC_ASSIGN_RIGHT -> state.copy(assignment = "${state.assignment[0]}${segChar(v)}")
        else -> state // V-pot rings (0x30–0x37) etc. are not shown in v1
    }

    private fun handleMeter(state: SurfaceState, v: Int): SurfaceState {
        val strip = v ushr 4
        if (strip !in 0 until Mcu.STRIPS) return state
        return when (val level = v and 0x0F) {
            in 0..0x0C -> state.copy(meters = state.meters.replaced(strip, level))
            0x0E -> state.copy(clips = state.clips.replaced(strip, true))
            0x0F -> state.copy(clips = state.clips.replaced(strip, false))
            else -> state
        }
    }

    private fun setLed(state: SurfaceState, note: Int, led: Led): SurfaceState =
        state.copy(leds = if (led == Led.OFF) state.leds - note else state.leds + (note to led), hostOnline = true)

    private fun ledFor(velocity: Int): Led = when (velocity) {
        0 -> Led.OFF
        1 -> Led.BLINK
        else -> Led.ON
    }

    private fun sysex(command: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xF0.toByte()) + Mcu.SYSEX_HEADER + byteArrayOf(deviceId.toByte(), command.toByte()) +
            payload + byteArrayOf(0xF7.toByte())

    companion object {
        private val CHALLENGE = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        /** Logic shows this as the surface version and expects it to start with 'V'. */
        private const val VERSION = "V1.00"

        private fun u(b: Byte) = b.toInt() and 0xFF

        /** LCD uses plain ASCII in 0x20–0x7F; anything else is shown as a space. */
        fun lcdChar(v: Int): Char = if (v in 0x20..0x7E) v.toChar() else ' '

        /** 7-segment display character set: 0x00–0x1F → '@'..'_', 0x20–0x3F → ASCII. Bit 6 = dot. */
        fun segChar(v: Int): Char {
            val c = v and 0x3F
            return if (c < 0x20) (c + 0x40).toChar() else c.toChar()
        }

        private fun <T> List<T>.replaced(i: Int, value: T): List<T> =
            if (i !in indices) this else toMutableList().also { it[i] = value }
    }
}
