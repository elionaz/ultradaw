package com.elionaz.ultradaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elionaz.ultradaw.SurfaceController
import com.elionaz.ultradaw.mcu.SurfaceState
import com.elionaz.ultradaw.rtpmidi.InviteFailure
import com.elionaz.ultradaw.rtpmidi.MidiService
import com.elionaz.ultradaw.rtpmidi.SessionState

@Composable
fun UltraDawScreen(controller: SurfaceController) {
    val surface by controller.surface.collectAsStateWithLifecycle()
    val connection by controller.connection.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val actions = remember(controller) {
        object : MixerActions {
            override fun press(note: Int, pressed: Boolean) = controller.press(note, pressed)
            override fun faderTouch(fader: Int, touched: Boolean) = controller.faderTouch(fader, touched)
            override fun faderMove(fader: Int, value: Int) = controller.faderMove(fader, value)
        }
    }

    val macs by controller.macs.collectAsStateWithLifecycle()
    val preferred by controller.preferredMac.collectAsStateWithLifecycle()
    val failure by controller.lastFailure.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        Rail(tab, { tab = it }, connection, surface, Modifier.width(84.dp).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (connection !is SessionState.Connected) {
                ConnectScreen(connection, macs, preferred, failure, controller::connectTo)
            } else when (tab) {
                0 -> TransportScreen(surface, actions::press)
                else -> MixerScreen(surface, actions)
            }
        }
    }
}

@Composable
private fun Rail(
    tab: Int,
    onTab: (Int) -> Unit,
    connection: SessionState,
    surface: SurfaceState,
    modifier: Modifier,
) {
    Column(
        modifier.background(Palette.Panel).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf("Transport", "Mixer").forEachIndexed { i, label ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (tab == i) Palette.PanelRaised else Color.Transparent)
                    .clickable { onTab(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (tab == i) Palette.Text else Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val (dot, text) = when {
            connection is SessionState.Connected && surface.hostOnline -> Palette.Play to "DAW"
            connection is SessionState.Connected -> Palette.Cycle to "MIDI"
            connection is SessionState.Failed -> Palette.Record to "Error"
            connection is SessionState.Connecting -> Palette.Cycle to "Link…"
            else -> Palette.TextDim to "Idle"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(" $text", color = Palette.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConnectScreen(
    connection: SessionState,
    macs: List<MidiService>,
    preferred: String?,
    failure: InviteFailure?,
    onPick: (MidiService) -> Unit,
) {
    val title = when {
        connection is SessionState.Failed -> connection.message
        connection is SessionState.Connecting -> "Connecting to ${connection.peerName}…"
        failure == InviteFailure.REJECTED -> "Your Mac refused the connection"
        macs.isEmpty() -> "Looking for your Mac…"
        else -> "Choose your Mac"
    }
    val help = when {
        failure == InviteFailure.REJECTED || failure == InviteFailure.NO_ANSWER ->
            "On the Mac: Audio MIDI Setup → Network → select your session, tick Enabled and set\n" +
                "“Who may connect to me” to Anyone. UltraDAW keeps retrying on its own."
        macs.isEmpty() ->
            "On the Mac: Audio MIDI Setup → Window → Show MIDI Studio → Network → create a session and tick Enabled.\n" +
                "In Logic Pro: Control Surfaces → Setup → New → Install → Mackie Control, ports = the network session."
        else -> "UltraDAW reconnects to the Mac you pick automatically from now on."
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                help, color = Palette.TextDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (macs.isNotEmpty()) {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                macs.take(3).forEach { mac ->
                    val isPreferred = mac.name == preferred
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPreferred) Palette.PanelRaised else Palette.Panel)
                            .clickable(enabled = connection !is SessionState.Connecting) { onPick(mac) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isPreferred) Palette.Play else Palette.TextDim))
                            Text("  ${mac.name}", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
