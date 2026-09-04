package com.lfq06.arknightsreader.library

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.ReadingCapabilities
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bookshelf state contract (Robolectric + in-memory Room). The VM exposes
 * suspend refresh/applyQuery so tests drive the exact sequence deterministically;
 * the UI wraps these in its own scope.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.inMemory(context)
        tempDir = java.nio.file.Files.createTempDirectory("library-test").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun book(id: String, title: String, lastOpenedAt: Long? = null, addedAt: Long = 0L) = Book(
        id = id, title = title, author = "Author", source = "hash:$id",
        format = BookFormat.TXT, formatVersion = 1,
        addedAt = addedAt, lastOpenedAt = lastOpenedAt,
        capabilities = ReadingCapabilities(reflow = true),
    )

    private fun vm() = LibraryViewModel(db, tempDir)

    @Test
    fun `loads books sorted recently opened first`() = runBlocking {
        db.bookDao().upsert(book("b-old", "Old Book", lastOpenedAt = 100L).toEntity())
        db.bookDao().upsert(book("b-new", "New Book", lastOpenedAt = 500L).toEntity())
        db.bookDao().upsert(book("b-never", "Never Opened", lastOpenedAt = null).toEntity())

        val vm = vm()
        vm.refresh()
        val titles = vm.books.value.map { it.book.title }
        assertEquals("recently opened first, never-opened last", listOf("New Book", "Old Book", "Never Opened"), titles)
    }

    @Test
    fun `search filters by title and author case-insensitively`() = runBlocking {
        db.bookDao().upsert(book("b1", "罗德岛纪行").toEntity())
        db.bookDao().upsert(book("b2", "Another Story").toEntity())
        val vm = vm()
        vm.refresh()

        vm.applyQuery("罗德")
        assertEquals(listOf("罗德岛纪行"), vm.books.value.map { it.book.title })

        vm.applyQuery("another")
        assertEquals(listOf("Another Story"), vm.books.value.map { it.book.title })

        vm.applyQuery("")
        assertEquals(2, vm.books.value.size)
    }

    @Test
    fun `remove deletes the book row`() = runBlocking {
        db.bookDao().upsert(book("b1", "Doomed").toEntity())
        val vm = vm()
        vm.refresh()

        vm.remove("b1")
        vm.refresh()
        assertTrue(vm.books.value.isEmpty())
        assertEquals(0, db.bookDao().queryAll().size)
    }
}
