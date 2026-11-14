package com.elionaz.ultradaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elionaz.ultradaw.mcu.Mcu
import com.elionaz.ultradaw.mcu.SurfaceState

@Composable
fun TransportScreen(state: SurfaceState, onPress: (Int, Boolean) -> Unit) {
    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimecodePanel(state, onPress, Modifier.weight(1.05f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("◀◀", Mcu.REWIND, Palette.Neutral, state, onPress)
                T("▶▶", Mcu.FAST_FWD, Palette.Neutral, state, onPress)
                T("■", Mcu.STOP, Palette.Neutral, state, onPress)
                T("▶", Mcu.PLAY, Palette.Play, state, onPress, weight = 1.4f)
                T("●", Mcu.RECORD, Palette.Record, state, onPress)
            }
            Row(Modifier.weight(0.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Cycle", Mcu.CYCLE, Palette.Cycle, state, onPress, small = true)
                T("Click", Mcu.CLICK, Palette.Cycle, state, onPress, small = true)
                T("Marker", Mcu.MARKER, Palette.Neutral, state, onPress, small = true)
                T("◀ Bank", Mcu.BANK_LEFT, Palette.Neutral, state, onPress, small = true)
                T("Bank ▶", Mcu.BANK_RIGHT, Palette.Neutral, state, onPress, small = true)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.T(
    label: String,
    note: Int,
    color: androidx.compose.ui.graphics.Color,
    state: SurfaceState,
    onPress: (Int, Boolean) -> Unit,
    weight: Float = 1f,
    small: Boolean = false,
) {
    McuButton(
        label, state.led(note), color, { onPress(note, it) },
        Modifier.weight(weight).fillMaxHeight(),
        fontSize = if (small) 14.sp else 26.sp,
    )
}

@Composable
private fun TimecodePanel(state: SurfaceState, onPress: (Int, Boolean) -> Unit, modifier: Modifier) {
    val beats = state.beatsMode
    val groups = listOf(0..2, 3..4, 5..6, 7..9)
    val labels = if (beats) listOf("BAR", "BEAT", "DIV", "TICK") else listOf("HR", "MIN", "SEC", "FRM")
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (beats) "BARS · BEATS" else "SMPTE", color = Palette.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            McuButton(
                if (beats) "→ SMPTE" else "→ BEATS", com.elionaz.ultradaw.mcu.Led.OFF, Palette.Neutral,
                { onPress(Mcu.SMPTE_BEATS, it) }, Modifier.width(96.dp).padding(vertical = 2.dp), fontSize = 12.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            groups.forEachIndexed { g, range ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        range.joinToString("") { state.timecode[it].toString() }.ifBlank { "-".repeat(range.count()) },
                        color = Palette.Lcd, fontSize = 64.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                    Text(labels[g], color = Palette.TextDim, fontSize = 11.sp)
                }
            }
        }
        val selected = (0 until Mcu.STRIPS).firstOrNull { state.isOn(Mcu.SELECT + it) }
        Text(
            selected?.let { "Selected: ${state.stripTop(it)}" } ?: " ",
            color = Palette.TextDim, fontSize = 12.sp, maxLines = 1,
        )
    }
}
