package com.justb81.compassduel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    secondary = Color(0xFF00E5FF),
    background = Color(0xFF0D0D1A),
    surface = Color(0xFF161626),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5E35B1),
    secondary = Color(0xFF0097A7),
)

/** Material 3 theme wrapper applied at the root of every screen. */
@Composable
fun CompassDuelTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
