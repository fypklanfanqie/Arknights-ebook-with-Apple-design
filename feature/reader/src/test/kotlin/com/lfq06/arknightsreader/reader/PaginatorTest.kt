package com.lfq06.arknightsreader.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pagination contract. Robolectric's StaticLayout font metrics are not
 * deterministic enough to assert page counts against, so tests inject a
 * deterministic LineMeasurer (fixed chars-per-line, fixed line height) and
 * lock the pagination LOGIC: block ordering, long-paragraph splitting,
 * Locator restoration after reflow, single/double column capacity, and
 * fingerprint-keyed caching. The production measurer itself is a thin
 * StaticLayout wrapper verified on-device.
 */
@RunWith(RobolectricTestRunner::class)
class PaginatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Width-proportional measurer: ~42px font, so chars scale with column width. */
    private fun fixedMeasurer(lineHeight: Float = 50f) = LineMeasurer { text, widthPx ->
        if (text.isEmpty()) return@LineMeasurer listOf("" to LineMeasurer.EMPTY_LINE_HEIGHT_PX.toFloat())
        val charsPerLine = (widthPx / 42).coerceAtLeast(1)
        text.chunked(charsPerLine).map { it to lineHeight }
    }

    private fun block(order: Int, text: String, kind: BlockKind = BlockKind.PARAGRAPH) = ContentBlock(
        id = "k$order", chapterId = "c1", orderIndex = order, kind = kind, text = text, imageRef = null,
    )

    private fun spec(pageW: Int = 420, pageH: Int = 640, mode: LayoutMode = LayoutMode.SINGLE, textSizePx: Int = 42) = LayoutSpec(
        pageWidthPx = pageW,
        pageHeightPx = pageH,
        marginsPx = 24,
        textSizePx = textSizePx,
        lineHeightFactor = 1.5f,
        mode = mode,
    )

    @Test
    fun `short blocks pack onto one page in order`() {
        val blocks = listOf(block(0, "第一段。"), block(1, "第二段。"))
        val pageMap = Paginator.paginate(context, blocks, spec(), fixedMeasurer())
        assertEquals(1, pageMap.pages.size)
        assertEquals(listOf("k0", "k1"), pageMap.pages[0].blockIds)
        assertEquals(0, pageMap.pages[0].startBlock)
    }

    @Test
    fun `many blocks overflow onto multiple pages in order`() {
        val blocks = (0 until 60).map { block(it, "这是第${it}段的内容，为了高度足够需要多写一些文字让每一页只放得下几段。") }
        val pageMap = Paginator.paginate(context, blocks, spec(), fixedMeasurer())
        assertTrue("60 paragraphs must span pages, got ${pageMap.pages.size}", pageMap.pages.size > 1)
        // Coverage: every block index appears at least once across page
        // ranges, in non-decreasing order. A block split across a page break
        // appears in BOTH pages' ranges (shared boundary), which is why the
        // sequence may repeat a value but must never go backwards.
        val seen = ArrayList<Int>()
        for (p in pageMap.pages) {
            val range = (p.startBlock..p.endBlock).toList()
            assertTrue("page ranges must not go backwards: $seen -> $range", range.isEmpty() || seen.isEmpty() || range.first() >= seen.last())
            seen.addAll(range)
        }
        assertEquals((0 until 60).toList(), seen.distinct())
    }

    @Test
    fun `single long paragraph splits across pages by characters`() {
        val longText = "长".repeat(4000)
        val pageMap = Paginator.paginate(context, listOf(block(0, longText)), spec(), fixedMeasurer())
        assertTrue("one 4000-char paragraph must split", pageMap.pages.size > 1)
        // Concatenated page text reconstructs the source.
        val rebuilt = pageMap.pages.joinToString("") { p -> p.lines.joinToString("") { it.text } }
        assertEquals(longText, rebuilt)
    }

    @Test
    fun `locator restores to the same block after reflow`() {
        val blocks = (0 until 40).map { block(it, "第${it}段：中文明日方舟内容测试文本。") }
        val small = Paginator.paginate(context, blocks, spec(pageH = 480), fixedMeasurer())
        val big = Paginator.paginate(context, blocks, spec(pageH = 800), fixedMeasurer())
        assertTrue("smaller page must hold fewer blocks", small.pages.size > big.pages.size)

        val anchorBlock = small.pages[2].startBlock
        val restored = big.pageForBlock(anchorBlock)
        assertTrue("anchor block must exist in the reflowed layout", restored >= 0)
        assertTrue(big.pages[restored].blockIds.contains("k$anchorBlock"))
    }

    @Test
    fun `double page halves the per-page capacity`() {
        val blocks = (0 until 40).map { block(it, "第${it}段：双页模式容量对比文本。") }
        // Double mode's narrower columns fit fewer of the 10-char lines.
        val single = Paginator.paginate(context, blocks, spec(mode = LayoutMode.SINGLE), fixedMeasurer())
        val double = Paginator.paginate(context, blocks, spec(mode = LayoutMode.DOUBLE), fixedMeasurer())
        assertTrue("double mode splits across two columns per page", double.pages.size > single.pages.size)
    }

    @Test
    fun `different fingerprints never share cache entries`() {
        val cache = PageCache()
        val blocks = listOf(block(0, "缓存测试。"))
        val a = cache.getOrCompute(context, blocks, spec()) { b, s -> Paginator.paginate(context, b, s, fixedMeasurer()) }
        val b = cache.getOrCompute(context, blocks, spec(textSizePx = 50)) { bl, s -> Paginator.paginate(context, bl, s, fixedMeasurer()) }
        assertTrue("different fingerprints must not collide", a !== b)
        val a2 = cache.getOrCompute(context, blocks, spec()) { bl, s -> Paginator.paginate(context, bl, s, fixedMeasurer()) }
        assertTrue("same fingerprint hits the cache", a === a2)
    }
}
