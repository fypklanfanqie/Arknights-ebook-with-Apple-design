package com.lfq06.arknightsreader.reader

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.LayoutMode

/**
 * One laid-out line drawn on a page: which block it came from, the character
 * range inside that block, and the text itself (for reconstruction/search).
 */
data class LaidLine(
    val blockId: String,
    val blockIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

/** One page: ordered lines plus the [startBlock..endBlock] block range. */
data class Page(
    val index: Int,
    val lineItems: List<LaidLine>,
    val startBlock: Int,
    val endBlock: Int,
) {
    val blockIds: List<String> get() = lineItems.map { it.blockId }.distinct()
}

/**
 * Immutable pagination result: pages in reading order plus helpers to map a
 * block index or a Locator-ish position back to a page.
 */
data class PageMap(
    val pages: List<Page>,
    val spec: LayoutSpec,
) {
    /** Page index containing [blockIndex], or -1. */
    fun pageForBlock(blockIndex: Int): Int =
        pages.indexOfFirst { blockIndex in it.startBlock..it.endBlock }
}

/**
 * Measured line source for [Paginator]. The real implementation wraps
 * StaticLayout; tests inject deterministic measurers so pagination
 * contracts do not depend on Robolectric's font metrics.
 */
fun interface LineMeasurer {
    /** Measures [text] laid out in [widthPx]; yields (lineText, heightPx) pairs. */
    fun lines(text: String, widthPx: Int): List<Pair<String, Float>>

    companion object {
        const val PARAGRAPH_SPACING_PX = 12
        const val IMAGE_SLOT_HEIGHT_PX = 220
        const val EMPTY_LINE_HEIGHT_PX = 24

        /** Production measurer over StaticLayout (real device metrics). */
        fun staticLayout(context: Context, textSizePx: Int, lineHeightFactor: Float): LineMeasurer {
            val paint = TextPaint().apply {
                textSize = textSizePx.toFloat()
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            return LineMeasurer { text, widthPx ->
                if (text.isEmpty()) return@LineMeasurer listOf("" to EMPTY_LINE_HEIGHT_PX.toFloat())
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, lineHeightFactor)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build()
                (0 until layout.lineCount).map { i ->
                    val start = layout.getLineStart(i)
                    val end = layout.getLineVisibleEnd(i)
                    val height = layout.getLineBottom(i) - layout.getLineTop(i)
                    text.substring(start.coerceAtMost(text.length), end.coerceAtMost(text.length)) to
                        (height + PARAGRAPH_SPACING_PX).toFloat()
                }
            }
        }
    }
}

/**
 * StaticLayout-based text paginator for reflowable books. Whole blocks are
 * measured into lines first; a block taller than a column splits naturally
 * across pages. All geometry derives from [LayoutSpec]; results key on its
 * fingerprint for caching.
 */
object Paginator {

    fun paginate(
        context: Context,
        blocks: List<ContentBlock>,
        spec: LayoutSpec,
        measurer: LineMeasurer = LineMeasurer.staticLayout(context, spec.textSizePx, spec.lineHeightFactor),
    ): PageMap {
        val columnWidth = when (spec.mode) {
            LayoutMode.SINGLE -> spec.pageWidthPx - spec.marginsPx * 2
            LayoutMode.DOUBLE -> (spec.pageWidthPx - spec.marginsPx * 2 - COLUMN_GAP_PX) / 2
        }.coerceAtLeast(1)
        val columnHeight = (spec.pageHeightPx - spec.marginsPx * 2).coerceAtLeast(1)

        val pages = ArrayList<Page>()
        var currentLines = ArrayList<LaidLine>()
        var usedHeight = 0f
        var currentStart = 0
        var currentEnd = -1

        fun flush() {
            if (currentLines.isEmpty()) return
            pages.add(Page(pages.size, currentLines.toList(), currentStart, currentEnd))
            currentLines = ArrayList()
            usedHeight = 0f
        }

        fun appendLine(line: LaidLine, height: Float) {
            if (usedHeight + height > columnHeight && currentLines.isNotEmpty()) {
                flush()
                currentStart = line.blockIndex
            }
            currentLines.add(line)
            usedHeight += height
            currentEnd = line.blockIndex
        }

        blocks.forEachIndexed { blockIndex, block ->
            if (block.kind == com.lfq06.arknightsreader.model.BlockKind.IMAGE_PLACEHOLDER) {
                appendLine(
                    LaidLine(block.id, blockIndex, 0, block.text.length, block.text.ifEmpty { "[图]" }),
                    LineMeasurer.IMAGE_SLOT_HEIGHT_PX.toFloat(),
                )
                return@forEachIndexed
            }
            val laid = measurer.lines(block.text, columnWidth)
            if (laid.isEmpty()) {
                appendLine(LaidLine(block.id, blockIndex, 0, 0, ""), LineMeasurer.EMPTY_LINE_HEIGHT_PX.toFloat())
                return@forEachIndexed
            }
            for ((lineText, height) in laid) {
                appendLine(
                    LaidLine(block.id, blockIndex, 0, block.text.length, lineText),
                    height,
                )
            }
        }
        flush()

        return PageMap(pages, spec)
    }

    private const val COLUMN_GAP_PX = 48
}
