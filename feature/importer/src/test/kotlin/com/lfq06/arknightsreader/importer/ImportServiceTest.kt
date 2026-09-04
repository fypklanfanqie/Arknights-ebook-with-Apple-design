package com.lfq06.arknightsreader.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.TurnStyle
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for the SAF import pipeline (Robolectric). The pipeline is
 * exercised through [ImportService] with a [FakeContentResolver]-style
 * provider registered against the Robolectric context: hash dedupe, format
 * dispatch, private copies, Room persistence, and failure cleanup.
 */
@RunWith(RobolectricTestRunner::class)
class ImportServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var service: ImportService
    private val provider = FakeOpenDocuments()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemory(context)
        service = ImportService(
            context = context,
            db = db,
            content = provider,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `importing a txt book persists book chapters blocks and fts`() = runTest {
        provider.register(
            "content://docs/book.txt",
            "第一章 开端\n甲之内容。\n\n第二章 转折\n乙之内容。".toByteArray(),
            displayName = "novel.txt",
        )
        val events = service.import("content://docs/book.txt").toList()
        assertTrue("import must end Done, got ${events.last()}", events.last() is ImportService.ImportProgress.Done)

        val books = db.bookDao().queryAll()
        assertEquals(1, books.size)
        val book = books[0].toModel()
        assertEquals("novel", book.title)
        assertEquals(BookFormat.TXT, book.format)
        // The persisted capabilities are the TXT module's declared ones.
        assertEquals(
            listOf(TurnStyle.PHYSICAL, TurnStyle.SIMPLE_FADE, TurnStyle.NONE),
            book.capabilities.turnStyles,
        )
        assertTrue(book.capabilities.reflow && book.capabilities.search && book.capabilities.annotate)

        val chapters = db.chapterDao().queryByBookOrdered(book.id)
        assertEquals(listOf("第一章 开端", "第二章 转折"), chapters.map { it.title })
        val blocks = db.blockDao().queryByChapterOrdered(chapters[0].id)
        assertEquals(listOf("甲之内容。"), blocks.map { it.text })

        // FTS mirrors block text for search.
        val hits = db.bookSearchDao().search(
            com.lfq06.arknightsreader.database.FtsQueryBuilder.toMatchQuery("甲之内容"),
            "甲之内容",
        )
        assertTrue("imported text must be searchable", hits.isNotEmpty())
    }

    @Test
    fun `duplicate source is rejected by hash`() = runTest {
        val bytes = "第一章 唯一\n内容。".toByteArray()
        provider.register("content://docs/a.txt", bytes, displayName = "a.txt")
        provider.register("content://docs/b.txt", bytes, displayName = "b.txt")
        service.import("content://docs/a.txt").toList()
        val events = service.import("content://docs/b.txt").toList()
        assertTrue("same content must fail as duplicate", events.last() is ImportService.ImportProgress.Failed)
        assertEquals(1, db.bookDao().queryAll().size)
    }

    @Test
    fun `failed parse cleans up partial rows`() = runTest {
        // 0x81/0x00 alternating bytes are undecodable in every supported
        // encoding (the TxtModule test pins this), so parse really throws.
        provider.register(
            "content://docs/garbage.bin",
            ByteArray(64) { if (it % 2 == 0) 0x81.toByte() else 0x00 },
            displayName = "garbage.bin",
        )
        val events = service.import("content://docs/garbage.bin").toList()
        assertTrue(events.last() is ImportService.ImportProgress.Failed)
        assertEquals("failed import must leave no book row", 0, db.bookDao().queryAll().size)
    }

    @Test
    fun `unresolvable uri fails cleanly`() = runTest {
        val events = service.import("content://docs/missing.txt").toList()
        assertTrue(events.last() is ImportService.ImportProgress.Failed)
        assertEquals(0, db.bookDao().queryAll().size)
    }
}
