package com.lfq06.arknightsreader.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room persistence schema (version 1). Field-for-field mirror of the pure
 * model types in `data/model`; converters in [Converters] bridge enums and
 * value objects. Physical schema drift starts a migration train here.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val source: String,
    val format: String,
    val formatVersion: Int,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progressPct: Double,
    val capabilitiesJson: String,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index("bookId", "orderIndex")],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val orderIndex: Int,
    val title: String,
    val spineId: String?,
    val href: String?,
)

@Entity(
    tableName = "content_blocks",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chapterId"), Index("chapterId", "orderIndex")],
)
data class ContentBlockEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val orderIndex: Int,
    val kind: String,
    val text: String,
    val imageRef: String?,
)

@Entity(tableName = "reading_positions", indices = [Index(value = ["bookId"], unique = true)])
data class ReadingPositionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val charOffset: Int,
    val progression: Double,
    val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId"), Index("bookId", "createdAt")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val charOffset: Int,
    val title: String?,
    val snippet: String,
    val createdAt: Long,
)

@Entity(tableName = "annotations", indices = [Index("bookId"), Index("chapterId", "blockId")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quote: String,
    val color: String?,
    val note: String?,
    val anchoredVersion: Int?,
    val orphaned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "book_settings", indices = [Index(value = ["bookId"], unique = true)])
data class BookSettingsEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val fontSize: Int,
    val lineHeight: Float,
    val fontWeight: Int,
    val margin: Int,
    val fontIndex: Int,
    val textAlign: String,
)

/** FTS4 external-content index over [ContentBlockEntity.text] for full-text search. */
@Fts4(contentEntity = ContentBlockEntity::class)
@Entity(tableName = "fts_blocks")
data class FtsEntry(
    val text: String,
)
