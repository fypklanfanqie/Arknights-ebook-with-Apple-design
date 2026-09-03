package com.lfq06.arknightsreader.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfq06.arknightsreader.model.Annotation
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.BookSettings
import com.lfq06.arknightsreader.model.Bookmark
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.ReadingPosition
import com.lfq06.arknightsreader.model.TurnStyle
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.database.Mappers.toModel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Room DAO contract tests (Robolectric + in-memory Room). Locks the persistence
 * behavior the importer/library/reader layers rely on: insertion ordering,
 * cascade delete, position upsert idempotency, orphan-annotation filtering,
 * annotation re-anchoring lookup, and FTS matching. Tests drive the DAOs with
 * pure model types through [Mappers], so the mapping seam is covered too.
 */
@RunWith(AndroidJUnit4::class)
class DaoContractTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(id: String, title: String = "Book $id") = Book(
        id = id,
        title = title,
        author = "Author",
        source = "file:///test/$id.txt",
        format = BookFormat.TXT,
        formatVersion = 1,
        capabilities = ReadingCapabilities(turnStyles = listOf(TurnStyle.PHYSICAL)),
    )

    private fun chapter(id: String, bookId: String, order: Int) = Chapter(
        id = id, bookId = bookId, orderIndex = order, title = "Ch $order", spineId = "s$order", href = null,
    )

    private fun block(id: String, chapterId: String, order: Int, text: String) = ContentBlock(
        id = id, chapterId = chapterId, orderIndex = order, kind = BlockKind.PARAGRAPH, text = text, imageRef = null,
    )

    @Test
    fun `book insert query update progress and delete`() = runBlocking {
        val dao = db.bookDao()
        dao.upsert(book("b1").toEntity())
        assertEquals("Book b1", dao.queryById("b1")?.toModel()?.title)

        dao.updateProgress("b1", 0.42, lastOpenedAt = 1000L)
        val updated = dao.queryById("b1")!!.toModel()
        assertEquals(0.42, updated.progressPct, 1e-9)
        assertEquals(1000L, updated.lastOpenedAt)
        // Round-trip preserves the capability enum list.
        assertEquals(listOf(TurnStyle.PHYSICAL), updated.capabilities.turnStyles)

        assertEquals(1, dao.queryAll().size)
        assertEquals(0, dao.countBySource("file:///other"))
        assertEquals(1, dao.countBySource("file:///test/b1.txt"))

        // Metadata update without touching the chapter tree (the old REPLACE
        // upsert would have cascaded it away).
        dao.updateMeta("b1", title = "Renamed", author = "New", coverPath = null, formatVersion = 2)
        val reloaded = dao.queryById("b1")!!.toModel()
        assertEquals("Renamed", reloaded.title)
        assertEquals(2, reloaded.formatVersion)
        assertEquals(0.42, reloaded.progressPct, 1e-9)

        dao.delete("b1")
        assertEquals(null, dao.queryById("b1"))
    }

    @Test
    fun `re-upsert of an existing book id aborts instead of cascading`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })

        // ABORT strategy: a second insert of the same id must throw, NOT
        // cascade-delete the chapter tree the way REPLACE did.
        var threw = false
        try {
            bookDao.upsert(book("b1", title = "Duplicate").toEntity())
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("re-insert must abort", threw)
        assertEquals("cascade must not fire on aborted insert", 1, chapterDao.queryByBookOrdered("b1").size)
    }

    @Test
    fun `chapters order by orderIndex and cascade with book`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(
            listOf(chapter("c2", "b1", 1), chapter("c0", "b1", 0), chapter("c1", "b1", 2)).map { it.toEntity() },
        )
        val ordered = chapterDao.queryByBookOrdered("b1")
        assertEquals("orderIndex must drive ordering", listOf("c0", "c2", "c1"), ordered.map { it.id })

        assertEquals("Ch 2", chapterDao.queryTitle("c1"))

        // Cascade: deleting the book removes its chapters.
        bookDao.delete("b1")
        assertTrue(chapterDao.queryByBookOrdered("b1").isEmpty())
    }

    @Test
    fun `blocks query by chapter ordered and cascade with chapter`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val blockDao = db.blockDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })
        blockDao.insertAll(
            listOf(
                block("k2", "c1", 2, "third"),
                block("k0", "c1", 0, "first"),
                block("k1", "c1", 1, "second"),
            ).map { it.toEntity() },
        )
        val blocks = blockDao.queryByChapterOrdered("c1")
        assertEquals(listOf("k0", "k1", "k2"), blocks.map { it.id })
        assertEquals("second", blockDao.queryById("k1")?.toModel()?.text)

        chapterDao.deleteByBook("b1")
        assertTrue("blocks cascade with their chapter", blockDao.queryByChapterOrdered("c1").isEmpty())
    }

    @Test
    fun `position upsert is idempotent per book`() = runBlocking {
        val bookDao = db.bookDao()
        val positionDao = db.positionDao()
        bookDao.upsert(book("b1").toEntity())
        val position = { blockId: String, offset: Int, updated: Long ->
            ReadingPosition(
                id = "p1", bookId = "b1", chapterId = "c1", blockId = blockId,
                charOffset = offset, progression = 0.1, updatedAt = updated,
            )
        }
        positionDao.upsert(position("k1", 10, 1L).toEntity())
        positionDao.upsert(position("k5", 99, 2L).toEntity())
        val p = positionDao.getByBook("b1")!!.toModel()
        assertEquals("k5", p.blockId)
        assertEquals(99, p.charOffset)

        positionDao.deleteByBook("b1")
        assertEquals(null, positionDao.getByBook("b1"))
    }

    @Test
    fun `position upsert deduplicates by bookId unique index`() = runBlocking {
        // Distinct ids but the same bookId: the unique bookId index (not the
        // PK) must drive the REPLACE so a book still owns exactly one row.
        val bookDao = db.bookDao()
        val positionDao = db.positionDao()
        bookDao.upsert(book("b1").toEntity())
        positionDao.upsert(
            ReadingPosition(
                id = "pa", bookId = "b1", chapterId = "c1", blockId = "k1",
                charOffset = 1, progression = 0.1, updatedAt = 1L,
            ).toEntity(),
        )
        positionDao.upsert(
            ReadingPosition(
                id = "pb", bookId = "b1", chapterId = "c2", blockId = "k9",
                charOffset = 2, progression = 0.9, updatedAt = 2L,
            ).toEntity(),
        )
        val only = positionDao.getByBook("b1")!!.toModel()
        assertEquals("pb", only.id)
        assertEquals("k9", only.blockId)
    }

    @Test
    fun `block replaceByChapter rewrites without stale fts rows`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val blockDao = db.blockDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })
        blockDao.insertAll(listOf(block("k1", "c1", 0, "dawn breaks over the tower")).map { it.toEntity() })

        // Re-import with different content under a new id.
        blockDao.replaceByChapter(
            "c1",
            listOf(block("k2", "c1", 0, "evening settles on the sea")).map { it.toEntity() },
        )
        val blocks = blockDao.queryByChapterOrdered("c1")
        assertEquals(listOf("k2"), blocks.map { it.id })

        // The old text must be gone from both search legs; the new one hit.
        val safeOld = FtsQueryBuilder.toMatchQuery("dawn")
        assertTrue("stale fts row must be gone", db.bookSearchDao().search(safeOld, "dawn").isEmpty())
        assertTrue(
            "new text must match via MATCH leg",
            db.bookSearchDao().search(FtsQueryBuilder.toMatchQuery("evening"), "evening").any { it.blockId == "k2" },
        )
    }

    @Test
    fun `bookmarks insert query delete by book order`() = runBlocking {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        bookDao.upsert(book("b1").toEntity())
        bookmarkDao.insert(
            Bookmark(
                id = "m2", bookId = "b1", chapterId = "c1", blockId = "k2",
                charOffset = 5, title = null, snippet = "later", createdAt = 2L,
            ).toEntity(),
        )
        bookmarkDao.insert(
            Bookmark(
                id = "m1", bookId = "b1", chapterId = "c0", blockId = "k0",
                charOffset = 1, title = "Early", snippet = "sooner", createdAt = 1L,
            ).toEntity(),
        )
        assertEquals(listOf("m1", "m2"), bookmarkDao.queryByBookOrdered("b1").map { it.id })
        bookmarkDao.delete("m1")
        assertEquals(listOf("m2"), bookmarkDao.queryByBookOrdered("b1").map { it.id })
    }

    @Test
    fun `annotations filter orphaned and re-anchor lookup`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val blockDao = db.blockDao()
        val annotationDao = db.annotationDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })
        blockDao.insertAll(listOf(block("k1", "c1", 0, "hello world")).map { it.toEntity() })

        val anchor = { id: String, isOrphaned: Boolean ->
            Annotation(
                id = id, bookId = "b1", chapterId = "c1", blockId = "k1",
                startOffset = 0, endOffset = 5, quote = "hello",
                color = "#FFCC00", note = null, anchoredVersion = 1, orphaned = isOrphaned,
                createdAt = 1L, updatedAt = 1L,
            )
        }
        annotationDao.insert(anchor("a1", false).toEntity())
        annotationDao.insert(anchor("a2", true).toEntity())
        assertEquals(listOf("a1"), annotationDao.queryByBook("b1").map { it.toModel() }.filter { !it.orphaned }.map { it.id })
        assertEquals(listOf("a2"), annotationDao.queryOrphaned(orphaned = true).map { it.toModel() }.map { it.id })

        // Re-anchoring lookup: find annotations overlapping a block range.
        val hits = annotationDao.queryByChapterBlockAnchor(chapterId = "c1", blockId = "k1", startOffset = 0, endOffset = 5)
        assertTrue("re-anchor lookup must find overlapping annotations", hits.map { it.toModel() }.any { it.id == "a1" })

        // Update note/color.
        annotationDao.updateNoteAndColor("a1", note = "remember", color = "#00FF00", updatedAt = 9L)
        val updated = annotationDao.queryByBook("b1").map { it.toModel() }.first { it.id == "a1" }
        assertEquals("remember", updated.note)
        assertEquals("#00FF00", updated.color)

        annotationDao.delete("a1")
        assertTrue(annotationDao.queryByBook("b1").map { it.toModel() }.none { it.id == "a1" })
    }

    @Test
    fun `settings upsert returns per-book overrides`() = runBlocking {
        val bookDao = db.bookDao()
        val settingsDao = db.settingsDao()
        bookDao.upsert(book("b1").toEntity())
        val settings = { fontSize: Int, align: com.lfq06.arknightsreader.model.TextAlign ->
            BookSettings(
                id = "s1", bookId = "b1", fontSize = fontSize, lineHeight = 1.6f, fontWeight = 400,
                margin = 16, fontIndex = 0, textAlign = align,
            )
        }
        settingsDao.upsert(settings(20, com.lfq06.arknightsreader.model.TextAlign.LEFT).toEntity())
        settingsDao.upsert(settings(24, com.lfq06.arknightsreader.model.TextAlign.JUSTIFY).toEntity())
        val loaded = settingsDao.getByBook("b1")!!.toModel()
        assertEquals(24, loaded.fontSize)
        assertEquals(com.lfq06.arknightsreader.model.TextAlign.JUSTIFY, loaded.textAlign)
    }

    @Test
    fun `fts search matches block text via both legs`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val blockDao = db.blockDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })
        blockDao.insertAll(
            listOf(
                block("k1", "c1", 0, "罗德岛的黎明"),
                block("k2", "c1", 1, "刀客塔在甲板上"),
                block("k3", "c1", 2, "The dawn breaks over the tower"),
            ).map { it.toEntity() },
        )
        // CJK: hits via the LIKE leg (simple tokenizer cannot substring-match).
        val cjk = db.bookSearchDao().search(FtsQueryBuilder.toMatchQuery("罗德岛"), "罗德岛")
        assertTrue("CJK query must hit via LIKE leg", cjk.any { it.blockId == "k1" })
        // English: hits via the MATCH leg (tokenizer splits on spaces).
        val en = db.bookSearchDao().search(FtsQueryBuilder.toMatchQuery("dawn"), "dawn")
        assertTrue("English word must hit via MATCH leg", en.any { it.blockId == "k3" })
        assertTrue(db.bookSearchDao().search(FtsQueryBuilder.toMatchQuery("不存在的词组"), "不存在的词组").isEmpty())
    }

    @Test
    fun `search survives raw fts operators in user input`() = runBlocking {
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val blockDao = db.blockDao()
        bookDao.upsert(book("b1").toEntity())
        chapterDao.insertAll(listOf(chapter("c1", "b1", 0)).map { it.toEntity() })
        blockDao.insertAll(listOf(block("k1", "c1", 0, "plain text block")).map { it.toEntity() })

        // Raw operator characters would throw a malformed MATCH exception if
        // passed through unquoted; the phrase wrapper keeps the query safe.
        for (raw in listOf("a OR b", "NEAR(", "no*tes", "quo\"te", "-negated", "OR")) {
            val safe = FtsQueryBuilder.toMatchQuery(raw)
            db.bookSearchDao().search(safe, raw) // must not throw
        }
        // A real term still matches after the operator storm.
        assertTrue(
            "real term still matches",
            db.bookSearchDao().search(FtsQueryBuilder.toMatchQuery("plain"), "plain").any { it.blockId == "k1" },
        )
    }
}
