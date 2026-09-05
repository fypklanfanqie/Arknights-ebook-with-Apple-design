package com.lfq06.arknightsreader.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Reader paper palette: surface + prose colors for one theme. */
data class ReaderColors(
    val paper: Color,
    val prose: Color,
    val accent: Color,
    val isDark: Boolean,
)

enum class ReaderThemeId { PARCHMENT, DARK, EYE_COMFORT, PURE_BLACK }

/** Theme registry; every theme guarantees 4.5:1 prose/paper contrast. */
object ReaderPalettes {
    val PARCHMENT = ReaderColors(
        paper = Color(0xFFF2E8D5),
        prose = Color(0xFF3A2F23),
        accent = Color(0xFF8C6A3F),
        isDark = false,
    )

    val DARK = ReaderColors(
        paper = Color(0xFF232019),
        prose = Color(0xFFD8CFC0),
        accent = Color(0xFFC9A96A),
        isDark = true,
    )

    val EYE_COMFORT = ReaderColors(
        paper = Color(0xFFCEDEC2),
        prose = Color(0xFF2B3A2E),
        accent = Color(0xFF4F7A56),
        isDark = false,
    )

    val PURE_BLACK = ReaderColors(
        paper = Color(0xFF000000),
        prose = Color(0xFFB0B0B0),
        accent = Color(0xFF8A8A8A),
        isDark = true,
    )

    fun forId(id: ReaderThemeId): ReaderColors = when (id) {
        ReaderThemeId.PARCHMENT -> PARCHMENT
        ReaderThemeId.DARK -> DARK
        ReaderThemeId.EYE_COMFORT -> EYE_COMFORT
        ReaderThemeId.PURE_BLACK -> PURE_BLACK
    }
}

/** WCAG relative-luminance contrast ratio between two colors (1..21). */
fun contrastRatio(a: Color, b: Color): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()

    fun luminance(c: Color): Float =
        0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)

    val la = luminance(a)
    val lb = luminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    // Identical luminances would yield 0/0; the WCAG floor clamps to 1.
    return if (lighter <= darker + 1e-9f) 1f else (lighter + 0.05f) / (darker + 0.05f)
}

/** App shape language: capsules and continuous large radii (glass-friendly). */
object ReaderShapes {
    val capsule = RoundedCornerShape(50)
    val toolbar = RoundedCornerShape(24.dp)
    val panel = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(12.dp)
}

val LocalReaderColors = staticCompositionLocalOf { ReaderPalettes.PARCHMENT }

/**
 * App theme: Material3 shell for controls + the reader paper palette via
 * [LocalReaderColors]. Falls back to the system dark preference when [id]
 * is not pinned.
 */
@Composable
fun ArknightsReaderTheme(
    id: ReaderThemeId? = null,
    content: @Composable () -> Unit,
) {
    val effective = id ?: if (isSystemInDarkTheme()) ReaderThemeId.DARK else ReaderThemeId.PARCHMENT
    val reader = ReaderPalettes.forId(effective)
    val scheme = if (reader.isDark) {
        darkColorScheme(
            background = reader.paper,
            surface = reader.paper,
            primary = reader.accent,
            onBackground = reader.prose,
        )
    } else {
        lightColorScheme(
            background = reader.paper,
            surface = reader.paper,
            primary = reader.accent,
            onBackground = reader.prose,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReaderColors provides reader) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
