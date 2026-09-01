package com.lfq06.arknightsreader.turngl

import com.lfq06.arknightsreader.turn.Vec2

/**
 * One drawable curl frame: geometry uniforms plus texture handles. Built by
 * the interaction layer from a [com.lfq06.arknightsreader.turn.CurlState] and
 * uploaded to the shader by [CurlGLRenderer.render].
 *
 * All fields are immutable; the renderer treats them as a pure description of
 * what to draw this tick.
 */
data class CurlFrameParams(
    /** Crease axis point in mesh (material) coordinates. */
    val axisPoint: Vec2,
    /** Unit normal of the crease axis in mesh coordinates. */
    val axisNormal: Vec2,
    /** Fold radius; 0 sharpens to a crease. */
    val radius: Double,
    /** Page size in mesh coordinates. */
    val pageW: Double,
    val pageH: Double,
    /**
     * Half paper thickness in pixels: the front face is drawn at +offset along
     * the deformed normal, the back face at -offset, so the two sheets never
     * fight for the same depth. Keep it sub-pixel (~0.35).
     */
    val halfThickness: Double = 0.35,
    /** Whether the whole page should be hidden (used for an empty idle frame). */
    val pageVisible: Boolean = true,
) {
    companion object {
        /** A frame that draws nothing: geometry present but page hidden. */
        fun idle(): CurlFrameParams = CurlFrameParams(
            axisPoint = Vec2(0.0, 0.0),
            axisNormal = Vec2(1.0, 0.0),
            radius = 0.0,
            pageW = 1.0,
            pageH = 1.0,
            halfThickness = 0.0,
            pageVisible = false,
        )
    }
}
