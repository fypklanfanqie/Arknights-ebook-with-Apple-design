package com.lfq06.arknightsreader.database

import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.BookSettings
import com.lfq06.arknightsreader.model.Bookmark
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.ReadingPosition
import com.lfq06.arknightsreader.model.TextAlign
import com.lfq06.arknightsreader.model.Annotation

/**
 * Row <-> model mappers. The database stores primitives and JSON; the rest of
 * the app sees only immutable model types.
 */
object Mappers {

    fun Book.toEntity() = BookEntity(
        id = id,
        title = title,
        author = author,
        source = source,
        format = format.name,
        formatVersion = formatVersion,
        coverPath = coverPath,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        progressPct = progressPct,
        capabilitiesJson = Converters().capabilitiesToJson(capabilities),
    )

    fun BookEntity.toModel(): Book {
        val converters = Converters()
        return Book(
            id = id,
            title = title,
            author = author,
            source = source,
            format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.UNKNOWN),
            formatVersion = formatVersion,
            coverPath = coverPath,
            addedAt = addedAt,
            lastOpenedAt = lastOpenedAt,
            progressPct = progressPct,
            capabilities = converters.jsonToCapabilities(capabilitiesJson),
        )
    }

    fun Chapter.toEntity() = ChapterEntity(
        id = id, bookId = bookId, orderIndex = orderIndex, title = title, spineId = spineId, href = href,
    )

    fun ChapterEntity.toModel() = Chapter(
        id = id, bookId = bookId, orderIndex = orderIndex, title = title, spineId = spineId, href = href,
    )

    fun ContentBlock.toEntity() = ContentBlockEntity(
        id = id, chapterId = chapterId, orderIndex = orderIndex, kind = kind.name, text = text, imageRef = imageRef,
    )

    fun ContentBlockEntity.toModel() = ContentBlock(
        id = id,
        chapterId = chapterId,
        orderIndex = orderIndex,
        kind = runCatching { BlockKind.valueOf(kind) }.getOrDefault(BlockKind.PARAGRAPH),
        text = text,
        imageRef = imageRef,
    )

    fun ReadingPosition.toEntity() = ReadingPositionEntity(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        charOffset = charOffset, progression = progression, updatedAt = updatedAt,
    )

    fun ReadingPositionEntity.toModel() = ReadingPosition(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        charOffset = charOffset, progression = progression, updatedAt = updatedAt,
    )

    fun Bookmark.toEntity() = BookmarkEntity(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        charOffset = charOffset, title = title, snippet = snippet, createdAt = createdAt,
    )

    fun BookmarkEntity.toModel() = Bookmark(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        charOffset = charOffset, title = title, snippet = snippet, createdAt = createdAt,
    )

    fun Annotation.toEntity() = AnnotationEntity(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        startOffset = startOffset, endOffset = endOffset, quote = quote,
        color = color, note = note, anchoredVersion = anchoredVersion,
        orphaned = orphaned, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun AnnotationEntity.toModel() = Annotation(
        id = id, bookId = bookId, chapterId = chapterId, blockId = blockId,
        startOffset = startOffset, endOffset = endOffset, quote = quote,
        color = color, note = note, anchoredVersion = anchoredVersion,
        orphaned = orphaned, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun BookSettings.toEntity() = BookSettingsEntity(
        id = id, bookId = bookId, fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, margin = margin, fontIndex = fontIndex, textAlign = textAlign.name,
    )

    fun BookSettingsEntity.toModel() = BookSettings(
        id = id, bookId = bookId, fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, margin = margin, fontIndex = fontIndex,
        textAlign = runCatching { TextAlign.valueOf(textAlign) }.getOrDefault(TextAlign.LEFT),
    )
}
