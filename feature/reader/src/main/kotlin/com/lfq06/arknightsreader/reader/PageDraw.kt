package com.lfq06.arknightsreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * One drawing contract for the static reading Canvas and the turn-engine
 * page textures. Both render from the same [Page], so the flipping page and
 * the resting page always show identical content, folios, and margins.
 */
object PageDraw {

    /** Text lines of [page] as the static renderer draws them. */
    fun staticText(page: Page): List<String> = page.lineItems.map { it.text }

    /** Text lines of [page] as the texture renderer draws them (same source). */
    fun textureText(page: Page): List<String> = staticText(page)

    /**
     * Renders [page] into a bitmap of [widthPx] x [heightPx]: paper
     * background, laid-out lines (wrapped via the same paginator geometry),
     * and a bottom-right folio with the page index.
     */
    fun renderPage(
        context: Context,
        page: Page,
        widthPx: Int,
        heightPx: Int,
        textSizePx: Int,
        paperColor: Int,
        proseColor: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paper = Paint().apply { color = paperColor }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paper)

        val text = Paint().apply {
            color = proseColor
            textSize = textSizePx.toFloat()
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val lineHeight = textSizePx * 1.6f
        var y = lineHeight
        val margin = textSizePx
        for (line in page.lineItems) {
            if (y > heightPx - margin) break
            canvas.drawText(line.text, margin.toFloat(), y, text)
            y += lineHeight
        }
        // Folio: page index bottom-right.
        val folio = Paint(text).apply {
            textSize = textSizePx * 0.7f
            color = proseColor and 0x00FFFFFF or (0x88000000.toInt())
        }
        canvas.drawText(
            (page.index + 1).toString(),
            widthPx - margin * 2f,
            heightPx - margin * 0.6f,
            folio,
        )
        return bitmap
    }
}
