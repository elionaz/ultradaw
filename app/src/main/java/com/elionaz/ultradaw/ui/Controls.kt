package com.elionaz.ultradaw.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elionaz.ultradaw.mcu.Led
import com.elionaz.ultradaw.mcu.Mcu

/**
 * A momentary MCU button: sends press on finger down and release on finger up,
 * lit according to the LED state reported by the host.
 */
@Composable
fun McuButton(
    label: String,
    led: Led,
    color: Color,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val blink = rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkAlpha",
    )
    val lit = when (led) {
        Led.ON -> 1f
        Led.BLINK -> blink.value
        Led.OFF -> 0f
    }
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        lit > 0f -> color.copy(alpha = 0.25f + 0.55f * lit)
        down -> Palette.PanelRaised.copy(alpha = 1f)
        else -> Palette.Panel
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (lit > 0f) color else Palette.Line, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    press(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        // Also runs if the gesture is cancelled, so the host never sees a stuck button.
                        press(false)
                        down = false
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (lit > 0.5f) Color.Black else Palette.Text,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Vertical fader with relative dragging (no jump on touch). Sends touch on/off so the
 * host lets go of the fader while it's held (needed for automation).
 */
@Composable
fun Fader(
    value: Int,
    onTouch: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Neutral,
) {
    val touch by rememberUpdatedState(onTouch)
    val move by rememberUpdatedState(onMove)
    var local by remember { mutableStateOf<Float?>(null) }
    val hostValue by rememberUpdatedState(value)
    val shown = local ?: (value.toFloat() / Mcu.FADER_MAX)

    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Touch is sent on finger down, not after the drag slop, so holding a fader
                    // still grabs it from automation in Touch/Latch modes.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var position = hostValue.toFloat() / Mcu.FADER_MAX
                    var lastSent = -1
                    local = position
                    touch(true)
                    try {
                        val travel = size.height - CAP_HEIGHT.toPx()
                        var pointer = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            change.consume()
                            if (dy == 0f) continue
                            position = (position - dy / travel).coerceIn(0f, 1f)
                            local = position
                            val value = (position * Mcu.FADER_MAX).toInt()
                            if (value != lastSent) {
                                lastSent = value
                                move(value)
                            }
                            pointer = change.id
                        }
                    } finally {
                        local = null
                        touch(false)
                    }
                }
            },
    ) {
        val capH = CAP_HEIGHT.toPx()
        val trackW = 4.dp.toPx()
        val cx = size.width / 2
        drawRoundRect(
            Palette.Line,
            topLeft = Offset(cx - trackW / 2, capH / 2),
            size = Size(trackW, size.height - capH),
            cornerRadius = CornerRadius(trackW),
        )
        val y = (1f - shown) * (size.height - capH)
        drawRoundRect(
            accent.copy(alpha = 0.35f),
            topLeft = Offset(cx - trackW / 2, y + capH / 2),
            size = Size(trackW, size.height - capH - y),
            cornerRadius = CornerRadius(trackW),
        )
        val capW = size.width * 0.9f
        drawRoundRect(
            if (local != null) accent else Palette.FaderCap,
            topLeft = Offset(cx - capW / 2, y),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawRect(
            if (local != null) Color.Black else accent,
            topLeft = Offset(cx - capW / 2 + 4.dp.toPx(), y + capH / 2 - 1.dp.toPx()),
            size = Size(capW - 8.dp.toPx(), 2.dp.toPx()),
        )
    }
}

private val CAP_HEIGHT = 26.dp

/** 12-segment MCU-style meter, with a clip LED on top. */
@Composable
fun Meter(level: Int, clip: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val segments = 12
        val gap = 2.dp.toPx()
        val clipH = 6.dp.toPx()
        val segH = (size.height - clipH - gap * segments) / segments
        drawRect(
            if (clip) Palette.MeterHigh else Palette.Line,
            size = Size(size.width, clipH),
        )
        for (s in 0 until segments) {
            val y = size.height - (s + 1) * (segH + gap) + gap
            val on = s < level
            val color = when {
                s >= 10 -> Palette.MeterHigh
                s >= 8 -> Palette.MeterMid
                else -> Palette.MeterLow
            }
            drawRect(if (on) color else color.copy(alpha = 0.12f), Offset(0f, y), Size(size.width, segH))
        }
    }
}

@Composable
fun Lcd(text: String, modifier: Modifier = Modifier, dim: Boolean = false, fontSize: TextUnit = 13.sp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text.ifBlank { "—" },
            color = if (dim) Palette.TextDim else Palette.Lcd,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.TextDim)
    }
}
