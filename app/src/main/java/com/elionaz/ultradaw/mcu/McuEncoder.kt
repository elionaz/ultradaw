package com.elionaz.ultradaw.mcu

/** Surface → host MCU messages. */
object McuEncoder {
    fun button(note: Int, pressed: Boolean): ByteArray =
        byteArrayOf(0x90.toByte(), note.toByte(), (if (pressed) 0x7F else 0x00).toByte())

    /** A full momentary press (down + up), as a physical button would send. */
    fun tap(note: Int): List<ByteArray> = listOf(button(note, true), button(note, false))

    fun faderTouch(fader: Int, touched: Boolean): ByteArray = button(Mcu.FADER_TOUCH + fader, touched)

    fun faderMove(fader: Int, value: Int): ByteArray {
        val v = value.coerceIn(0, Mcu.FADER_MAX)
        return byteArrayOf((0xE0 + fader).toByte(), (v and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte())
    }
}
