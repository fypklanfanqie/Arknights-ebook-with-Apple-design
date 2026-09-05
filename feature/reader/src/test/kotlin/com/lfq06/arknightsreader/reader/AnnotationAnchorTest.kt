package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.model.Annotation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Annotation anchor contract (Robolectric + in-memory Room): reflow-stable
 * re-anchoring via quote+context, orphan marking when the source changes,
 * and range-overlap lookup for rendering highlights on a page.
 */
@RunWith(RobolectricTestRunner::class)
class AnnotationAnchorTest {
    private lateinit var db: AppDatabase

    /** The source text a note was anchored against ("anchoredVersion 1"). */
    private val v1Text = "罗德岛的黎明从甲板上升起。"
    private val v2Text = "罗德岛的黄昏在甲板上降临。" // mutated source

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed() = runBlocking {
        db.bookDao().upsert(
            com.lfq06.arknightsreader.model.Book(
                id = "b1", title = "T", author = "A", source = "h", format = com.lfq06.arknightsreader.model.BookFormat.TXT,
            ).toEntity(),
        )
        db.chapterDao().insertAll(
            listOf(
                com.lfq06.arknightsreader.model.Chapter("c1", "b1", 0, "Ch", null, null),
            ).map { it.toEntity() },
        )
        db.blockDao().insertAll(
            listOf(
                com.lfq06.arknightsreader.model.ContentBlock("k1", "c1", 0, com.lfq06.arknightsreader.model.BlockKind.PARAGRAPH, v1Text, null),
            ).map { it.toEntity() },
        )
    }

    private fun annotation(start: Int, end: Int, id: String = "a1") = Annotation(
        id = id, bookId = "b1", chapterId = "c1", blockId = "k1",
        startOffset = start, endOffset = end, quote = v1Text.substring(start, end),
        color = "#FFCC00", note = null, anchoredVersion = 1, orphaned = false,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `anchor resolves to the same range when text is unchanged`() = runBlocking {
        seed()
        val repo = AnnotationAnchor(db)
        val a = annotation(0, 4) // "罗德岛的黎"
        db.annotationDao().insert(a.toEntity())

        val resolved = repo.reanchor(a, currentText = v1Text, version = 1)
        assertEquals(a.startOffset, resolved.startOffset)
        assertEquals(a.endOffset, resolved.endOffset)
        assertTrue(!resolved.orphaned)
    }

    @Test
    fun `anchor relocates by quote after reflow`() = runBlocking {
        seed()
        val repo = AnnotationAnchor(db)
        // Same quote, but offsets in the block shifted (e.g. prefix text removed).
        val shiftedText = "前缀。罗德岛的黎明从甲板上升起。"
        val a = annotation(0, 4).copy(quote = "罗德岛的黎")
        db.annotationDao().insert(a.toEntity())

        val resolved = repo.reanchor(a, currentText = shiftedText, version = 2)
        assertTrue("quote must re-anchor the annotation", !resolved.orphaned)
        assertEquals(shiftedText.indexOf("罗德岛的黎"), resolved.startOffset)
        assertEquals(shiftedText.indexOf("罗德岛的黎") + 5, resolved.endOffset)
        assertEquals(2, resolved.anchoredVersion)
    }

    @Test
    fun `missing quote marks the annotation orphaned`() = runBlocking {
        seed()
        val repo = AnnotationAnchor(db)
        // (0, 5) = "罗德岛的黎" — the 黎 is absent from v2Text, so the quote
        // truly vanishes (an earlier (0,4) slice, 罗德岛的, still existed in
        // v2Text and legitimately re-anchored at 0).
        val a = annotation(0, 5)
        db.annotationDao().insert(a.toEntity())

        val resolved = repo.reanchor(a, currentText = v2Text, version = 2)
        assertTrue("quote vanished with the edit; annotation must orphan", resolved.orphaned)
        // Orphaned annotations are still persisted, flagged for the UI.
        assertEquals(1, db.annotationDao().queryOrphaned(true).size)
    }

    @Test
    fun `highlights on a page come from range overlap lookup`() = runBlocking {
        seed()
        val repo = AnnotationAnchor(db)
        db.annotationDao().insert(annotation(0, 4, id = "a1").toEntity())
        db.annotationDao().insert(annotation(6, 10, id = "a2").toEntity())

        // Page renders block k1 lines [0, 5): only the first annotation overlaps.
        val page0 = annotation(0, 4)
        val hits = repo.highlightsFor(chapterId = "c1", blockId = "k1", startOffset = 0, endOffset = 5)
        assertEquals(listOf(page0.id), hits.map { it.id })
    }
}
