package com.lfq06.arknightsreader.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Glass intensity model shared by every surface. [GlassMode] mirrors the
 * user setting: OFF renders the standard opaque surface, SIMPLIFIED drops
 * the refraction/sheen layers (cheap blur only), FULL renders everything.
 */
enum class GlassMode { FULL, SIMPLIFIED, OFF }

enum class GlassPreset { RESTRAINED, CLEAR, SOFT }

/** Resolved glass parameters for one render. */
data class GlassParams(
    val blurRadiusDp: Float,
    val tintAlpha: Float,
    val sheenAlpha: Float,
    val borderAlpha: Float,
) {
    companion object {
        fun forPreset(preset: GlassPreset): GlassParams = when (preset) {
            GlassPreset.RESTRAINED -> GlassParams(blurRadiusDp = 16f, tintAlpha = 0.62f, sheenAlpha = 0.10f, borderAlpha = 0.28f)
            GlassPreset.CLEAR -> GlassParams(blurRadiusDp = 24f, tintAlpha = 0.42f, sheenAlpha = 0.16f, borderAlpha = 0.36f)
            GlassPreset.SOFT -> GlassParams(blurRadiusDp = 32f, tintAlpha = 0.52f, sheenAlpha = 0.22f, borderAlpha = 0.32f)
        }
    }
}

/**
 * Reader tool-layer surface: the single place that decides glass vs opaque.
 *
 * The FULL mode renders a translucent frosted panel (tint + top sheen +
 * hairline border) over whatever is behind it. This is an app-owned
 * implementation behind a stable API, so a stronger refraction backend can
 * be swapped in later without touching call sites. Prose is NEVER placed on
 * a glass surface — call sites must only use this for toolbars, progress
 * pills, and settings panels.
 */
@Composable
fun ReaderSurface(
    mode: GlassMode,
    preset: GlassPreset,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    when (mode) {
        GlassMode.OFF -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(surfaceColor),
                content = content,
            )
        }
        GlassMode.SIMPLIFIED -> {
            val params = GlassParams.forPreset(preset)
            Box(
                modifier = modifier
                    .clip(shape)
                    .glassTint(surfaceColor, params.tintAlpha.coerceAtLeast(0.55f))
                    .glassBorder(params.borderAlpha),
                content = content,
            )
        }
        GlassMode.FULL -> {
            val params = GlassParams.forPreset(preset)
            Box(
                modifier = modifier
                    .clip(shape)
                    .glassTint(surfaceColor, params.tintAlpha)
                    .glassSheen(params.sheenAlpha)
                    .glassBorder(params.borderAlpha),
                content = content,
            )
        }
    }
}

private fun Modifier.glassTint(color: Color, alpha: Float): Modifier =
    background(color.copy(alpha = alpha))

private fun Modifier.glassSheen(alpha: Float): Modifier =
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = alpha),
                    Color.Transparent,
                ),
            ),
        )
    }

private fun Modifier.glassBorder(alpha: Float): Modifier =
    drawWithContent {
        drawContent()
        drawRoundRect(
            color = Color.White.copy(alpha = alpha),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )
    }
