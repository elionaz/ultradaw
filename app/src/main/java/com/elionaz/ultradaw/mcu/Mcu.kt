package com.elionaz.ultradaw.mcu

/**
 * Mackie Control Universal (MCU) protocol constants, as spoken by Logic Pro and most DAWs.
 * Buttons and LEDs are note numbers on MIDI channel 1 (press = 0x7F, release = 0x00;
 * LED feedback: 0x00 off, 0x01 blink, 0x7F on).
 */
object Mcu {
    const val STRIPS = 8
    const val MASTER = 8 // fader index of the master fader (pitch bend channel 9)

    // Per-strip buttons (add strip index 0–7)
    const val REC_ARM = 0x00
    const val SOLO = 0x08
    const val MUTE = 0x10
    const val SELECT = 0x18

    // Bank / channel navigation
    const val BANK_LEFT = 0x2E
    const val BANK_RIGHT = 0x2F
    const val CHANNEL_LEFT = 0x30
    const val CHANNEL_RIGHT = 0x31

    // Display
    const val NAME_VALUE = 0x34
    const val SMPTE_BEATS = 0x35

    // Transport
    const val MARKER = 0x54
    const val CYCLE = 0x56
    const val CLICK = 0x59
    const val REWIND = 0x5B
    const val FAST_FWD = 0x5C
    const val STOP = 0x5D
    const val PLAY = 0x5E
    const val RECORD = 0x5F

    // Fader touch (add fader index 0–8)
    const val FADER_TOUCH = 0x68

    // LEDs that only the host drives
    const val LED_SMPTE = 0x71
    const val LED_BEATS = 0x72

    // Timecode digits: CC 0x40 (rightmost) … 0x49 (leftmost); assignment display CC 0x4A/0x4B
    const val CC_TIMECODE_FIRST = 0x40
    const val CC_TIMECODE_LAST = 0x49
    const val CC_ASSIGN_RIGHT = 0x4A
    const val CC_ASSIGN_LEFT = 0x4B

    const val FADER_MAX = 0x3FFF

    // SysEx: F0 00 00 66 <device> <command> … F7
    val SYSEX_HEADER = byteArrayOf(0x00, 0x00, 0x66)
    const val DEVICE_MCU = 0x14
    const val SYSEX_DEVICE_QUERY = 0x00
    const val SYSEX_HOST_QUERY = 0x01
    const val SYSEX_HOST_REPLY = 0x02
    const val SYSEX_CONFIRM = 0x03
    const val SYSEX_ERROR = 0x04
    const val SYSEX_GO_OFFLINE = 0x0F
    const val SYSEX_LCD = 0x12
    const val SYSEX_VERSION_REQUEST = 0x13
    const val SYSEX_VERSION_REPLY = 0x14
    const val SYSEX_FADERS_MIN = 0x61
    const val SYSEX_LEDS_OFF = 0x62
    const val SYSEX_RESET = 0x63

    const val LCD_ROW = 56
    const val LCD_SIZE = LCD_ROW * 2
    const val CELL = 7
}

enum class Led { OFF, ON, BLINK }

data class SurfaceState(
    /** Two rows of 56 characters, 7 per strip. */
    val lcd: String = " ".repeat(Mcu.LCD_SIZE),
    /** 14-bit positions; index 8 = master. */
    val faders: List<Int> = List(Mcu.STRIPS + 1) { 0 },
    /** Meter level 0–12 per strip. */
    val meters: List<Int> = List(Mcu.STRIPS) { 0 },
    val clips: List<Boolean> = List(Mcu.STRIPS) { false },
    val leds: Map<Int, Led> = emptyMap(),
    /** Ten timecode characters, index 0 = leftmost. */
    val timecode: List<Char> = List(10) { ' ' },
    val timecodeDots: List<Boolean> = List(10) { false },
    val assignment: String = "  ",
    /** True once the host completed the MCU handshake (or started talking to us). */
    val hostOnline: Boolean = false,
) {
    fun led(note: Int): Led = leds[note] ?: Led.OFF
    fun isOn(note: Int): Boolean = led(note) != Led.OFF

    fun stripTop(i: Int): String = lcd.substring(i * Mcu.CELL, i * Mcu.CELL + Mcu.CELL).trim()
    fun stripBottom(i: Int): String =
        lcd.substring(Mcu.LCD_ROW + i * Mcu.CELL, Mcu.LCD_ROW + i * Mcu.CELL + Mcu.CELL).trim()

    val beatsMode: Boolean get() = isOn(Mcu.LED_BEATS) || !isOn(Mcu.LED_SMPTE)
}
