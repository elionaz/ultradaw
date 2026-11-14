package com.elionaz.ultradaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elionaz.ultradaw.mcu.Led
import com.elionaz.ultradaw.mcu.Mcu
import com.elionaz.ultradaw.mcu.SurfaceState

interface MixerActions {
    fun press(note: Int, pressed: Boolean)
    fun faderTouch(fader: Int, touched: Boolean)
    fun faderMove(fader: Int, value: Int)
}

@Composable
fun MixerScreen(state: SurfaceState, actions: MixerActions) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until Mcu.STRIPS) {
            Strip(i, state, actions, Modifier.weight(1f).fillMaxHeight())
        }
        MasterStrip(state, actions, Modifier.weight(0.9f).fillMaxHeight())
        Column(Modifier.width(64.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Nav("◀ Bank", Mcu.BANK_LEFT, actions, Modifier.weight(1f))
            Nav("Bank ▶", Mcu.BANK_RIGHT, actions, Modifier.weight(1f))
            Nav("◀ Ch", Mcu.CHANNEL_LEFT, actions, Modifier.weight(1f))
            Nav("Ch ▶", Mcu.CHANNEL_RIGHT, actions, Modifier.weight(1f))
            Nav("Name", Mcu.NAME_VALUE, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Strip(i: Int, state: SurfaceState, actions: MixerActions, modifier: Modifier) {
    val selected = state.isOn(Mcu.SELECT + i)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.PanelRaised else Palette.Panel)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        McuButton(
            state.stripTop(i).ifEmpty { "${i + 1}" }, state.led(Mcu.SELECT + i), Palette.Select,
            { actions.press(Mcu.SELECT + i, it) }, Modifier.fillMaxWidth().height(24.dp), fontSize = 12.sp,
        )
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Meter(state.meters[i], state.clips[i], Modifier.width(7.dp).fillMaxHeight())
            Fader(
                state.faders[i],
                onTouch = { actions.faderTouch(i, it) },
                onMove = { actions.faderMove(i, it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                accent = if (selected) Palette.Select else Palette.Neutral,
            )
            Column(Modifier.width(30.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Small("M", Mcu.MUTE + i, Palette.Mute, state, actions, Modifier.weight(1f))
                Small("S", Mcu.SOLO + i, Palette.Solo, state, actions, Modifier.weight(1f))
                Small("R", Mcu.REC_ARM + i, Palette.Record, state, actions, Modifier.weight(1f))
            }
        }
        Lcd(state.stripBottom(i), Modifier.fillMaxWidth().height(14.dp), dim = true, fontSize = 10.sp)
    }
}

@Composable
private fun MasterStrip(state: SurfaceState, actions: MixerActions, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Panel).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Lcd("MASTER", Modifier.fillMaxWidth().height(24.dp), fontSize = 12.sp)
        Fader(
            state.faders[Mcu.MASTER],
            onTouch = { actions.faderTouch(Mcu.MASTER, it) },
            onMove = { actions.faderMove(Mcu.MASTER, it) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            accent = Palette.Play,
        )
    }
}

@Composable
private fun Small(label: String, note: Int, color: androidx.compose.ui.graphics.Color, state: SurfaceState, actions: MixerActions, modifier: Modifier) {
    McuButton(label, state.led(note), color, { actions.press(note, it) }, modifier.fillMaxWidth(), fontSize = 13.sp)
}

@Composable
private fun Nav(label: String, note: Int, actions: MixerActions, modifier: Modifier) {
    McuButton(label, Led.OFF, Palette.Neutral, { actions.press(note, it) }, modifier.fillMaxWidth(), fontSize = 12.sp)
}
