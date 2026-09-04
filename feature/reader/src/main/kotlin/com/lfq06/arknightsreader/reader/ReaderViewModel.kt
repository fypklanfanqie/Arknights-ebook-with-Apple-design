package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.ReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Reader state machine: opens a book, paginates its chapters, restores the
 * saved Locator after reflow, and persists reading positions. Page rendering
 * (Canvas/turn engine) is the view layer's job; the VM owns pure navigation
 * and restore logic so tests lock it deterministically.
 *
 * Pagination is injected as [paginateChapter] so tests substitute the
 * deterministic-measurer Paginator with any geometry.
 */
class ReaderViewModel(
    private val db: AppDatabase,
    private val paginateChapter: (chapterIndex: Int, spec: LayoutSpec) -> PageMap,
) {
    data class OpenBook(
        val bookId: String,
        val chapterIndex: Int,
        val pageMap: PageMap,
        val chapterCount: Int,
    )

    private val _state = MutableStateFlow<OpenBook?>(null)
    val state: StateFlow<OpenBook?> = _state

    /** Current page inside the open chapter's [PageMap]. */
    var currentPage: Int = 0
        private set

    private var chapterIds: List<String> = emptyList()
    private var blocksByChapter: Map<String, List<ContentBlock>> = emptyMap()
    private var spec: LayoutSpec = LayoutSpec(1080, 1920, 48, 42, 1.5f, com.lfq06.arknightsreader.model.LayoutMode.SINGLE)

    /**
     * Opens [bookId] at its saved Locator (chapter 0 when none), paginates
     * that chapter under [layout], and lands on the page holding the saved
     * block (page 0 otherwise).
     */
    suspend fun open(bookId: String, layout: LayoutSpec) {
        spec = layout
        val book = db.bookDao().queryById(bookId)?.toModel() ?: return
        val chapters = db.chapterDao().queryByBookOrdered(bookId)
        chapterIds = chapters.map { it.id }
        blocksByChapter = chapterIds.associateWith { cid ->
            db.blockDao().queryByChapterOrdered(cid).map { it.toModel() }
        }

        val saved = db.positionDao().getByBook(bookId)
        val chapterIndex = saved?.let { s -> chapterIds.indexOf(s.chapterId).takeIf { it >= 0 } } ?: 0
        val pageMap = paginateChapter(chapterIndex, spec)
        val pageIndex = saved?.blockId?.let { blockId ->
            pageMap.pages.indexOfFirst { p -> p.lineItems.any { it.blockId == blockId } }
                .takeIf { it >= 0 }
        } ?: 0
        _state.value = OpenBook(bookId, chapterIndex, pageMap, chapters.size)
        currentPage = pageIndex.coerceAtLeast(0)
        savePosition()
    }

    /** Next page, crossing into the next chapter at the end. */
    suspend fun nextPage(): Boolean {
        val current = _state.value ?: return false
        if (currentPage < current.pageMap.pages.size - 1) {
            currentPage += 1
            savePosition()
            return true
        }
        if (current.chapterIndex + 1 < current.chapterCount) {
            openChapter(current.bookId, current.chapterIndex + 1)
            return true
        }
        return false
    }

    /** Previous page, crossing into the previous chapter at the start. */
    suspend fun prevPage(): Boolean {
        val current = _state.value ?: return false
        if (currentPage > 0) {
            currentPage -= 1
            savePosition()
            return true
        }
        if (current.chapterIndex - 1 >= 0) {
            openChapterAtEnd(current.bookId, current.chapterIndex - 1)
            return true
        }
        return false
    }

    /**
     * Repaginates under a new [layout], restoring to the page holding the
     * first line of the page being read (Locator-style restore).
     */
    suspend fun relayout(layout: LayoutSpec) {
        val current = _state.value ?: return
        val anchorBlock = current.pageMap.pages.getOrNull(currentPage)
            ?.lineItems?.firstOrNull()?.blockId
        spec = layout
        val pageMap = paginateChapter(current.chapterIndex, spec)
        _state.value = current.copy(pageMap = pageMap)
        currentPage = anchorBlock?.let { blockId ->
            pageMap.pages.indexOfFirst { p -> p.lineItems.any { it.blockId == blockId } }
        }?.coerceAtLeast(0) ?: 0
        savePosition()
    }

    private suspend fun openChapter(bookId: String, chapterIndex: Int) {
        val pageMap = paginateChapter(chapterIndex, spec)
        val s = _state.value
        _state.value = s?.copy(chapterIndex = chapterIndex, pageMap = pageMap)
            ?: OpenBook(bookId, chapterIndex, pageMap, chapterIds.size)
        currentPage = 0
        savePosition()
    }

    private suspend fun openChapterAtEnd(bookId: String, chapterIndex: Int) {
        val pageMap = paginateChapter(chapterIndex, spec)
        val s = _state.value
        _state.value = s?.copy(chapterIndex = chapterIndex, pageMap = pageMap)
            ?: OpenBook(bookId, chapterIndex, pageMap, chapterIds.size)
        currentPage = (pageMap.pages.size - 1).coerceAtLeast(0)
        savePosition()
    }

    /** Persists the current page's first line as a Locator-style position. */
    private suspend fun savePosition() {
        val current = _state.value ?: return
        val page = current.pageMap.pages.getOrNull(currentPage) ?: return
        val first = page.lineItems.firstOrNull() ?: return
        val cid = chapterIds.getOrNull(current.chapterIndex) ?: return
        db.positionDao().upsert(
            ReadingPosition(
                id = "pos-${current.bookId}",
                bookId = current.bookId,
                chapterId = cid,
                blockId = first.blockId,
                charOffset = first.startOffset,
                progression = if (current.pageMap.pages.size <= 1) 0.0
                else currentPage.toDouble() / (current.pageMap.pages.size - 1),
                updatedAt = System.currentTimeMillis(),
            ).toEntity(),
        )
    }
}
