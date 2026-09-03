package com.lfq06.arknightsreader.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookDao {
    /**
     * First-insert only. REPLACE here would fire the FK CASCADE on re-upsert
     * of an existing id and wipe the whole chapter/block tree, so re-inserts
     * throw and metadata updates go through [updateProgress]/[updateMeta].
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun upsert(book: BookEntity)

    @Query(
        "UPDATE books SET title = :title, author = :author, coverPath = :coverPath, " +
            "formatVersion = :formatVersion WHERE id = :id",
    )
    suspend fun updateMeta(
        id: String,
        title: String,
        author: String,
        coverPath: String?,
        formatVersion: Int,
    )

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
    /**
     * Fresh insert for newly parsed books. REPLACE on an existing chapter id
     * would fire the FK CASCADE and silently wipe its blocks; re-import must
     * delete chapters first ([deleteByBook]) then insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
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
    /** Fresh insert for newly parsed chapters (ABORT surfaces id collisions loudly). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(blocks: List<ContentBlockEntity>)

    /**
     * Full rewrite of one chapter's blocks (re-import path). Delete-then-
     * insert rather than REPLACE: REPLACE's implicit delete does NOT fire the
     * FTS4 external-content sync triggers (recursive_triggers is off), which
     * would leave stale rows in fts_blocks.
     */
    @Query("DELETE FROM content_blocks WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    suspend fun replaceByChapter(chapterId: String, blocks: List<ContentBlockEntity>) {
        deleteByChapter(chapterId)
        insertAll(blocks)
    }

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
     * Full-text search over block text. [matchQuery] must already be a safe
     * FTS4 MATCH expression — build it with [FtsQueryBuilder.toMatchQuery],
     * which quotes user input as a phrase so raw operators like OR/NEAR/`"`
     * cannot break the statement. The WHERE clause ORs MATCH (useful for
     * space-delimited languages) with a LIKE substring fallback that also
     * covers CJK queries.
     */
    @Query(
        "SELECT cb.id AS blockId, cb.text AS snippet FROM content_blocks cb " +
            "WHERE cb.id IN (SELECT rowid FROM fts_blocks WHERE fts_blocks.text MATCH :matchQuery) " +
            "OR cb.text LIKE '%' || :likeQuery || '%' LIMIT :limit",
    )
    suspend fun search(matchQuery: String, likeQuery: String, limit: Int = 50): List<BlockSearchHit>
}

/** Builds FTS4-safe MATCH expressions from raw user input. */
object FtsQueryBuilder {
    /**
     * Wraps [input] as a quoted FTS4 phrase. Embedded double quotes are
     * doubled per FTS4 phrase syntax; empty input falls back to a phrase no
     * real token contains, so the MATCH leg matches nothing but never throws.
     */
    fun toMatchQuery(input: String): String {
        val sanitized = input.replace("\"", "\"\"").trim()
        if (sanitized.isEmpty()) return "\"  \""
        return "\"$sanitized\""
    }
}
