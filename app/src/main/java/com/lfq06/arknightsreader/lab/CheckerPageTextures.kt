package com.lfq06.arknightsreader.lab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Generates the diagnostic page textures for the curl lab: a UV checkerboard
 * with corner markers and a page-number caption. Pure Bitmap-in/Bitmap-out so
 * the geometry rules stay unit-testable via [CheckerPageTextures.layout].
 */
object CheckerPageTextures {

    data class Layout(
        val width: Int,
        val height: Int,
        val cellsX: Int,
        val cellsY: Int,
        val cellW: Int,
        val cellH: Int,
    )

    /** Derives the checker layout; clamps degenerate inputs to a 1x1 grid. */
    fun layout(width: Int, height: Int, cellsX: Int = 8, cellsY: Int = 10): Layout {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val cx = cellsX.coerceAtLeast(1)
        val cy = cellsY.coerceAtLeast(1)
        return Layout(w, h, cx, cy, w / cx, h / cy)
    }

    /** Parity of the cell that contains the normalized UV point. */
    fun cellParity(layout: Layout, u: Float, v: Float): Boolean {
        val cx = (u.coerceIn(0f, 0.9999f) * layout.cellsX).toInt()
        val cy = (v.coerceIn(0f, 0.9999f) * layout.cellsY).toInt()
        return (cx + cy) % 2 == 0
    }

    /**
     * Builds one page-face texture. [baseA]/[baseB] alternate per cell;
     * corner brackets and a caption are drawn on top so curl orientation and
     * mirroring are visible at a glance.
     */
    fun create(
        layout: Layout,
        caption: String,
        baseA: Int,
        baseB: Int,
        ink: Int = Color.rgb(20, 24, 28),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cell = Paint()
        // Ceiling semantics: integer cellW truncates, which would leave a
        // transparent strip on the right/bottom edge; stretch the last
        // row/column to the bitmap edge instead.
        for (iy in 0 until layout.cellsY) {
            val top = (iy * layout.cellH).toFloat()
            val bottom = if (iy == layout.cellsY - 1) {
                layout.height.toFloat()
            } else {
                ((iy + 1) * layout.cellH).toFloat()
            }
            for (ix in 0 until layout.cellsX) {
                val left = (ix * layout.cellW).toFloat()
                val right = if (ix == layout.cellsX - 1) {
                    layout.width.toFloat()
                } else {
                    ((ix + 1) * layout.cellW).toFloat()
                }
                cell.color = if ((ix + iy) % 2 == 0) baseA else baseB
                canvas.drawRect(left, top, right, bottom, cell)
            }
        }
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        // Corner brackets: 24px arms.
        val arm = (layout.width.coerceAtMost(layout.height) / 12).coerceIn(12, 48).toFloat()
        val t = (layout.width / 90).coerceIn(2, 6).toFloat()
        drawCorner(canvas, inkPaint, 0f, 0f, arm, arm, t)
        drawCorner(canvas, inkPaint, layout.width.toFloat(), 0f, -arm, arm, t)
        drawCorner(canvas, inkPaint, 0f, layout.height.toFloat(), arm, -arm, t)
        drawCorner(canvas, inkPaint, layout.width.toFloat(), layout.height.toFloat(), -arm, -arm, t)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = layout.height / 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(caption, layout.width / 2f, layout.height * 0.52f, text)
        return bmp
    }

    private fun drawCorner(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
        t: Float,
    ) {
        canvas.drawRect(
            minOf(cx, cx + dx),
            cy,
            maxOf(cx, cx + dx),
            cy + t * (if (dy < 0) -1f else 1f),
            paint,
        )
        canvas.drawRect(
            cx,
            minOf(cy, cy + dy),
            cx + t * (if (dx < 0) -1f else 1f),
            maxOf(cy, cy + dy),
            paint,
        )
    }
}
