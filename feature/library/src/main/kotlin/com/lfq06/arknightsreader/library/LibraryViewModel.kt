package com.lfq06.arknightsreader.library

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.model.Book
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One shelf card: the book plus its cover file (if a cover was stored). */
data class BookUi(
    val book: Book,
    val coverFile: File?,
)

/**
 * Bookshelf state holder: loads all books, applies a title/author search,
 * and exposes remove. [refresh]/[applyQuery] are public suspend functions so
 * tests can drive them deterministically; the UI triggers them from its own
 * scope.
 */
class LibraryViewModel(
    private val db: AppDatabase,
    private val coversDir: File,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    /** UI-owned scope for fire-and-forget mutations (remove). */
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val allBooks = MutableStateFlow<List<Book>>(emptyList())
    private val query = MutableStateFlow("")
    private val booksInternal = MutableStateFlow<List<BookUi>>(emptyList())

    /** Current shelf contents (filtered), ordered by the DAO. */
    val books: StateFlow<List<BookUi>> = booksInternal

    /** Reloads from the database and re-applies the current query. */
    suspend fun refresh() {
        allBooks.value = db.bookDao().queryAll().map { it.toModel() }
        applyQuery(query.value)
    }

    /** Sets the search text and re-filters the shelf. */
    suspend fun applyQuery(text: String) {
        query.value = text
        val q = text.trim()
        val filtered = if (q.isEmpty()) allBooks.value else allBooks.value.filter {
            it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
        }
        booksInternal.value = filtered.map { book ->
            BookUi(book = book, coverFile = File(coversDir, "${book.id}.png").takeIf { it.exists() })
        }
    }

    /** Convenience for the UI: fire-and-forget search. */
    fun search(text: String) {
        scope.launch { applyQuery(text) }
    }

    /** Deletes the book and reloads the shelf. */
    suspend fun remove(bookId: String) {
        db.bookDao().delete(bookId)
        refresh()
    }
}
