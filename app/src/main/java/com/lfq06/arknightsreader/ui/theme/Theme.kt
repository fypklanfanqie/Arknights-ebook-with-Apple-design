package com.lfq06.arknightsreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF315C8A),
    secondary = Color(0xFF526170),
    tertiary = Color(0xFF6C5677),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FA),
    secondary = Color(0xFFB8C8DA),
    tertiary = Color(0xFFD6B9DE),
)

@Composable
fun ArknightsReaderTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ReaderTypography,
        content = content,
    )
}
