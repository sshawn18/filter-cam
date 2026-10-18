package com.filtercam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Accent = Color(0xFFFFC83D)
    val Panel = Color(0xFF161616)
    val PanelSelected = Color(0xFF2A2A2A)
}

private val Dark = darkColorScheme(
    primary = Palette.Accent, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF121212), onSurface = Color.White,
    surfaceVariant = Palette.Panel, onSurfaceVariant = Color(0xFFB8B8B8),
    error = Color(0xFFFF6B6B),
)

@Composable
fun FilterCamTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Dark, content = content)
