package com.lfq06.arknightsreader.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun queryById(id: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
    suspend fun queryAll(): List<BookEntity>

    @Query("UPDATE books SET progressPct = :progressPct, lastOpenedAt = :lastOpenedAt WHERE id = :id")
    suspend fun updateProgress(id: String, progressPct: Double, lastOpenedAt: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM books WHERE source = :source")
    suspend fun countBySource(source: String): Int
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun queryByBookOrdered(bookId: String): List<ChapterEntity>

    @Query("SELECT title FROM chapters WHERE id = :chapterId")
    suspend fun queryTitle(chapterId: String): String?

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface BlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blocks: List<ContentBlockEntity>)

    @Query("SELECT * FROM content_blocks WHERE chapterId = :chapterId ORDER BY orderIndex ASC")
    suspend fun queryByChapterOrdered(chapterId: String): List<ContentBlockEntity>

    @Query("SELECT * FROM content_blocks WHERE id = :id")
    suspend fun queryById(id: String): ContentBlockEntity?

    @Query("SELECT COUNT(*) FROM content_blocks WHERE chapterId = :chapterId")
    suspend fun countByChapter(chapterId: String): Int
}

@Dao
interface PositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId LIMIT 1")
    suspend fun getByBook(bookId: String): ReadingPositionEntity?

    @Query("DELETE FROM reading_positions WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt ASC")
    suspend fun queryByBookOrdered(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: String): BookmarkEntity?
}

@Dao
interface AnnotationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(annotation: AnnotationEntity)

    @Query("UPDATE annotations SET note = :note, color = :color, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteAndColor(id: String, note: String?, color: String?, updatedAt: Long)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt ASC")
    suspend fun queryByBook(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE orphaned = :orphaned")
    suspend fun queryOrphaned(orphaned: Boolean): List<AnnotationEntity>

    @Query(
        "SELECT * FROM annotations WHERE chapterId = :chapterId AND blockId = :blockId " +
            "AND startOffset < :endOffset AND endOffset > :startOffset",
    )
    suspend fun queryByChapterBlockAnchor(
        chapterId: String,
        blockId: String,
        startOffset: Int,
        endOffset: Int,
    ): List<AnnotationEntity>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: BookSettingsEntity)

    @Query("SELECT * FROM book_settings WHERE bookId = :bookId LIMIT 1")
    suspend fun getByBook(bookId: String): BookSettingsEntity?
}

data class BlockSearchHit(
    val blockId: String,
    val snippet: String,
)

@Dao
interface BookSearchDao {
    /**
     * Full-text search over block text. The FTS4 default (simple) tokenizer
     * cannot substring-match CJK, so the WHERE clause ORs MATCH (useful for
     * space-delimited languages) with a LIKE substring fallback that also
     * covers CJK queries.
     */
    @Query(
        "SELECT cb.id AS blockId, cb.text AS snippet FROM content_blocks cb " +
            "WHERE cb.id IN (SELECT rowid FROM fts_blocks WHERE fts_blocks.text MATCH :query) " +
            "OR cb.text LIKE '%' || :query || '%' LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 50): List<BlockSearchHit>
}
