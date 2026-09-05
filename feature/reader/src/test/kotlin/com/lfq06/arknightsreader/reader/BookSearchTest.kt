package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.FtsQueryBuilder
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-book search contract (Robolectric + FTS4): snippet extraction, block-id
 * hits across chapters, and operator-safe queries over raw user input.
 */
@RunWith(RobolectricTestRunner::class)
class BookSearchTest {
    private lateinit var db: AppDatabase

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
            Book("b1", "搜索之书", "A", "h", BookFormat.TXT, 1).toEntity(),
        )
        db.chapterDao().insertAll(
            listOf(
                Chapter("c1", "b1", 0, "第一章", null, null),
                Chapter("c2", "b1", 1, "第二章", null, null),
            ).map { it.toEntity() },
        )
        db.blockDao().insertAll(
            listOf(
                ContentBlock("k1", "c1", 0, BlockKind.PARAGRAPH, "罗德岛的黎明从甲板上升起。", null),
                ContentBlock("k2", "c2", 0, BlockKind.PARAGRAPH, "刀客塔在黄昏回望罗德岛。", null),
                ContentBlock("k3", "c2", 1, BlockKind.PARAGRAPH, " unrelated english dawn here ", null),
            ).map { it.toEntity() },
        )
    }

    @Test
    fun `search finds all blocks containing the term across chapters`() = runBlocking {
        seed()
        val repo = BookSearcher(db)
        val hits = repo.search("罗德岛")
        // Both CJK hits come from the LIKE leg; order is row order.
        assertEquals(listOf("k1", "k2"), hits.map { it.blockId })
    }

    @Test
    fun `search returns nonblank snippets`() = runBlocking {
        seed()
        val repo = BookSearcher(db)
        val hits = repo.search("dawn")
        assertEquals(1, hits.size)
        assertTrue("snippet must carry context, got ${hits[0].snippet}", hits[0].snippet.contains("dawn"))
    }

    @Test
    fun `raw operator input never crashes the query`() = runBlocking {
        seed()
        val repo = BookSearcher(db)
        for (raw in listOf("OR", "a* AND b", "NEAR(", "quo\"te", "-")) {
            repo.search(raw) // must not throw
        }
        // A real term still matches after the operator storm.
        assertTrue(repo.search("黎明").isNotEmpty())
    }

    @Test
    fun `no-hit query returns empty`() = runBlocking {
        seed()
        val repo = BookSearcher(db)
        assertTrue(repo.search("不存在的词组组合").isEmpty())
    }
}
