package com.lfq06.arknightsreader.reader

import android.content.Context
import android.graphics.Bitmap
import com.lfq06.arknightsreader.turngl.CurlEglFrame
import com.lfq06.arknightsreader.turngl.CurlFrameParams
import com.lfq06.arknightsreader.turn.TurnGesture

/**
 * Bridges the reader's laid-out book pages into the turn engine: renders the
 * front/back page bitmaps for the current spread and forwards touch flow
 * into [TurnGesture], solving curl states and drawing through CurlEglFrame.
 *
 * Drag-path invariants (inherited from the turn engine):
 * - zero pagination, zero bitmap RE-generation, zero DB access per drag
 *   frame (page bitmaps are rendered once per spread before dragging);
 * - the render thread owns solve/build/upload.
 */
class PageTurnBridge(
    private val textureView: com.lfq06.arknightsreader.turngl.CurlTextureView,
    private val pageW: Float,
    private val pageH: Float,
) {
    /**
     * Renders the two page faces for the current spread and uploads them.
     * Called when a spread settles (never mid-drag).
     */
    fun prepareSpread(front: Page, back: Page?, context: Context, paperColor: Int, proseColor: Int, textSizePx: Int) {
        val frontBmp = PageDraw.renderPage(context, front, pageW.toInt().coerceAtLeast(64), pageH.toInt().coerceAtLeast(64), textSizePx, paperColor, proseColor)
        val backBmp = back?.let {
            PageDraw.renderPage(context, it, pageW.toInt().coerceAtLeast(64), pageH.toInt().coerceAtLeast(64), textSizePx, paperColor, proseColor)
        } ?: frontBmp
        CurlEglFrame.uploadTexture(frontBmp, backBmp)
        textureView.setFrameParams(idleParams())
    }

    private fun idleParams(): CurlFrameParams = CurlFrameParams(
        axisPoint = com.lfq06.arknightsreader.turn.Vec2(pageW / 2.0, 0.0),
        axisNormal = com.lfq06.arknightsreader.turn.Vec2(1.0, 0.0),
        radius = 0.0,
        pageW = pageW.toDouble(),
        pageH = pageH.toDouble(),
        halfThickness = 0.35,
        pageVisible = true,
    )
}
