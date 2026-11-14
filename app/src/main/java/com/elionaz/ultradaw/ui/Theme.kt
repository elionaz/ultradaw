package com.elionaz.ultradaw.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0D0E11)
    val Panel = Color(0xFF17191E)
    val PanelRaised = Color(0xFF22252C)
    val Line = Color(0xFF2E323A)
    val FaderCap = Color(0xFF3C414B)
    val Text = Color(0xFFE8EAEE)
    val TextDim = Color(0xFF8A909C)
    val Lcd = Color(0xFF7FE0C2)

    val Play = Color(0xFF3DDC84)
    val Record = Color(0xFFFF4D4D)
    val Cycle = Color(0xFFFFC53D)
    val Mute = Color(0xFF4DA3FF)
    val Solo = Color(0xFFFFD84D)
    val Select = Color(0xFFB98CFF)
    val Neutral = Color(0xFFCDD2DB)

    val MeterLow = Color(0xFF3DDC84)
    val MeterMid = Color(0xFFFFC53D)
    val MeterHigh = Color(0xFFFF4D4D)
}

@Composable
fun UltraDawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Background,
            surface = Palette.Panel,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            primary = Palette.Play,
        ),
        content = content,
    )
}
