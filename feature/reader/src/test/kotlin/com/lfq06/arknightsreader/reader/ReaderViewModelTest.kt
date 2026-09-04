package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.LayoutMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reader state contract (Robolectric + in-memory Room): open/restore,
 * page navigation with chapter crossing, and Locator-style relayout
 * restoration. Pagination is injected deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTest {
    private lateinit var db: AppDatabase

    /** Fixed pages-per-chapter paginator: [pages] pages, each one line of block k<i>. */
    private fun fixedPaginator(pages: Int) = { chapterIndex: Int, spec: LayoutSpec ->
        val lines = (0 until pages).map { i ->
            LaidLine("k${chapterIndex}-$i", i, 0, 1, "line")
        }
        val pageList = (0 until pages).map { i ->
            Page(i, listOf(lines[i]), i, i)
        }
        PageMap(pageList, spec)
    }

    /** Real paginator over the seeded blocks using the deterministic measurer. */
    private val realPaginator = { chapterIndex: Int, spec: LayoutSpec ->
        val blocks = blocksByChapter(chapterIndex)
        Paginator.paginate(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            blocks,
            spec,
        ) { text, widthPx ->
            val chars = (widthPx / 42).coerceAtLeast(1)
            if (text.isEmpty()) listOf("" to LineMeasurer.EMPTY_LINE_HEIGHT_PX.toFloat())
            else text.chunked(chars).map { it to 50f }
        }.let { pm ->
            // Re-key block ids to the seeded per-chapter scheme.
            PageMap(
                pm.pages.map { p ->
                    p.copy(lineItems = p.lineItems.map { l -> l.copy(blockId = blocks.getOrNull(l.blockIndex)?.id ?: l.blockId) })
                },
                pm.spec,
            )
        }
    }

    private var blocksByChapter: (Int) -> List<ContentBlock> = { emptyList() }

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedBook(bookId: String, chapters: Int, blocksPerChapter: Int) {
        blocksByChapter = { ci ->
            (0 until blocksPerChapter).map { b ->
                ContentBlock(
                    id = "k$ci-$b", chapterId = "$bookId-c$ci", orderIndex = b,
                    kind = BlockKind.PARAGRAPH,
                    text = "第${b}段内容文本用于分页测量需要足够长度让每页只放几段。",
                    imageRef = null,
                )
            }
        }
        runBlocking {
            db.bookDao().upsert(
                Book(
                    id = bookId, title = "T", author = "A", source = "hash:x",
                    format = BookFormat.TXT, formatVersion = 1,
                    capabilities = com.lfq06.arknightsreader.model.ReadingCapabilities(reflow = true),
                ).toEntity(),
            )
            for (c in 0 until chapters) {
                db.chapterDao().insertAll(
                    listOf(
                        Chapter(
                            id = "$bookId-c$c", bookId = bookId, orderIndex = c,
                            title = "Ch $c", spineId = null, href = null,
                        ).toEntity(),
                    ),
                )
                db.blockDao().insertAll(blocksByChapter(c).map { it.toEntity() })
            }
        }
    }

    private fun spec() = LayoutSpec(420, 640, 24, 42, 1.5f, LayoutMode.SINGLE)

    @Test
    fun `open without a saved position starts at chapter 0 page 0`() = runBlocking {
        seedBook("b1", chapters = 2, blocksPerChapter = 20)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec())
        val s = vm.state.value!!
        assertEquals(0, s.chapterIndex)
        assertEquals(0, vm.currentPage)
    }

    @Test
    fun `navigation advances and crosses chapters`() = runBlocking {
        seedBook("b1", chapters = 2, blocksPerChapter = 40)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec())
        val startChapter = vm.state.value!!.chapterIndex

        // Walk to the end of the chapter.
        while (vm.currentPage < vm.state.value!!.pageMap.pages.size - 1) {
            assertTrue(vm.nextPage())
        }
        // One more next crosses into the next chapter.
        if (vm.state.value!!.chapterIndex + 1 < vm.state.value!!.chapterCount) {
            assertTrue(vm.nextPage())
            assertEquals(startChapter + 1, vm.state.value!!.chapterIndex)
            assertEquals(0, vm.currentPage)
        }
    }

    @Test
    fun `prev from chapter start lands at previous chapter end`() = runBlocking {
        seedBook("b1", chapters = 2, blocksPerChapter = 40)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec())
        // Jump to chapter 1 page 0 by nexting across the boundary.
        while (vm.nextPage()) { /* until exhausted */ }
        // We are at the very end of the last chapter; walk back one.
        assertTrue(vm.prevPage())
        // Walk back to a chapter boundary and verify prev crosses backwards.
        var crossed = false
        while (vm.prevPage()) {
            if (vm.state.value!!.chapterIndex == 0) {
                crossed = true
                break
            }
        }
        assertTrue("prev must cross back into chapter 0", crossed)
    }

    @Test
    fun `relayout restores the anchor block page`() = runBlocking {
        seedBook("b1", chapters = 1, blocksPerChapter = 60)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec(pageH = 480))
        val anchorBlock = vm.state.value!!.pageMap.pages[2].lineItems.first().blockId
        // Advance one page, then relayout bigger; the anchor must still resolve.
        vm.relayout(spec(pageH = 900))
        val restored = vm.state.value!!.pageMap.pages.getOrNull(vm.currentPage)
        assertTrue(
            "relayout must restore to the anchor block's page",
            restored?.lineItems?.any { it.blockId == anchorBlock } == true,
        )
    }

    @Test
    fun `open with saved position restores chapter and page`() = runBlocking {
        seedBook("b1", chapters = 2, blocksPerChapter = 40)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec())
        // Advance into chapter 1.
        while (vm.nextPage()) { /* advance */ }
        // Re-open: the saved position must restore to the same chapter.
        val savedChapter = vm.state.value!!.chapterIndex
        val vm2 = ReaderViewModel(db, realPaginator)
        vm2.open("b1", spec())
        assertEquals(savedChapter, vm2.state.value!!.chapterIndex)
        // The restored page contains the saved block id.
        val savedBlock = db.positionDao().getByBook("b1")!!.blockId
        val page = vm2.state.value!!.pageMap.pages.getOrNull(vm2.currentPage)
        assertTrue(page?.lineItems?.any { it.blockId == savedBlock } == true)
    }

    @Test
    fun `next at the very end returns false`() = runBlocking {
        seedBook("b1", chapters = 1, blocksPerChapter = 10)
        val vm = ReaderViewModel(db, realPaginator)
        vm.open("b1", spec())
        while (vm.nextPage()) { /* advance */ }
        assertFalse(vm.nextPage())
    }
}

private fun spec(pageH: Int) = LayoutSpec(420, pageH, 24, 42, 1.5f, LayoutMode.SINGLE)
