package com.lfq06.arknightsreader.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    @Test
    fun `book constructs with required fields and reads them back`() {
        val caps = ReadingCapabilities(
            reflow = true,
            font = true,
            background = false,
            search = true,
            annotate = false,
            turnStyles = listOf(TurnStyle.PHYSICAL, TurnStyle.SIMPLE_FADE),
        )
        val book = Book(
            id = "book-1",
            title = "Volume 1",
            author = "Someone",
            source = "import:file",
            format = BookFormat.EPUB,
            formatVersion = 3,
            coverPath = "/covers/vol1.jpg",
            addedAt = 1000L,
            lastOpenedAt = 2000L,
            progressPct = 0.42,
            capabilities = caps,
        )

        assertEquals("book-1", book.id)
        assertEquals("Volume 1", book.title)
        assertEquals("Someone", book.author)
        assertEquals("import:file", book.source)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(3, book.formatVersion)
        assertEquals("/covers/vol1.jpg", book.coverPath)
        assertEquals(1000L, book.addedAt)
        assertEquals(2000L, book.lastOpenedAt)
        assertEquals(0.42, book.progressPct)
        assertEquals(caps, book.capabilities)
    }

    @Test
    fun `book applies field defaults`() {
        val book = Book(id = "book-2", title = "T", author = "A", source = "S")

        assertEquals(BookFormat.UNKNOWN, book.format)
        assertEquals(1, book.formatVersion)
        assertNull(book.coverPath)
        assertEquals(0L, book.addedAt)
        assertNull(book.lastOpenedAt)
        assertEquals(0.0, book.progressPct, 0.0)
        assertFalse(book.capabilities.reflow)
        assertTrue(book.capabilities.turnStyles.isEmpty())
    }

    @Test
    fun `chapter and contentBlock construct and default optionals`() {
        val chapter = Chapter(id = "ch-1", bookId = "book-1", orderIndex = 0, title = "Intro")
        assertNull(chapter.spineId)
        assertNull(chapter.href)

        val block = ContentBlock(
            id = "bl-1",
            chapterId = "ch-1",
            orderIndex = 0,
            kind = BlockKind.PARAGRAPH,
            text = "Hello",
        )
        assertNull(block.imageRef)
        assertEquals(BlockKind.PARAGRAPH, block.kind)
    }

    @Test
    fun `locator components are extractable`() {
        val locator = Locator(
            bookId = "book-1",
            chapterId = "ch-1",
            blockId = "bl-1",
            charOffset = 12,
            progression = 0.25,
        )

        val (bookId, chapterId, blockId, charOffset, progression) = locator
        assertEquals(locator.bookId, bookId)
        assertEquals(locator.chapterId, chapterId)
        assertEquals(locator.blockId, blockId)
        assertEquals(locator.charOffset, charOffset)
        assertEquals(locator.progression, progression, 0.0)
    }

    @Test
    fun `readingPosition constructs and reads fields`() {
        val pos = ReadingPosition(
            id = "rp-1",
            bookId = "book-1",
            chapterId = "ch-1",
            blockId = "bl-1",
            charOffset = 5,
            progression = 0.9,
            updatedAt = 3000L,
        )
        assertEquals("rp-1", pos.id)
        assertEquals("ch-1", pos.chapterId)
        assertEquals(5, pos.charOffset)
        assertEquals(0.9, pos.progression)
        assertEquals(3000L, pos.updatedAt)
    }

    @Test
    fun `bookmark constructs with nullable title and snippet`() {
        val bookmark = Bookmark(
            id = "bm-1",
            bookId = "book-1",
            chapterId = "ch-1",
            blockId = "bl-1",
            charOffset = 3,
            title = "First line",
            snippet = "Hello world",
            createdAt = 4000L,
        )
        assertEquals("First line", bookmark.title)
        assertEquals("Hello world", bookmark.snippet)
        assertEquals(4000L, bookmark.createdAt)

        val untitled = Bookmark(id = "bm-2", bookId = "book-1", chapterId = "ch-1", blockId = "bl-1")
        assertNull(untitled.title)
    }

    @Test
    fun `annotation constructs and defaults nullable fields`() {
        val annotation = Annotation(
            id = "an-1",
            bookId = "book-1",
            chapterId = "ch-1",
            blockId = "bl-1",
            startOffset = 2,
            endOffset = 7,
            quote = "Hello",
            color = "#ff0000",
            note = "highlight",
            anchoredVersion = 1,
            orphaned = false,
            createdAt = 5000L,
            updatedAt = 5000L,
        )
        assertEquals("an-1", annotation.id)
        assertEquals(2, annotation.startOffset)
        assertEquals(7, annotation.endOffset)
        assertEquals("#ff0000", annotation.color)
        assertEquals("highlight", annotation.note)
        assertEquals(1, annotation.anchoredVersion)
        assertFalse(annotation.orphaned)

        val float = Annotation(
            id = "an-2",
            bookId = "book-1",
            chapterId = "ch-1",
            blockId = "bl-1",
            startOffset = 0,
            endOffset = 1,
            quote = "H",
        )
        assertNull(float.color)
        assertNull(float.note)
        assertNull(float.anchoredVersion)
        assertFalse(float.orphaned)
    }

    @Test
    fun `bookSettings defaults apply`() {
        val settings = BookSettings(id = "bs-1", bookId = "book-1")
        assertEquals(16, settings.fontSize)
        assertEquals(1.4f, settings.lineHeight)
        assertEquals(400, settings.fontWeight)
        assertEquals(0, settings.margin)
        assertEquals(0, settings.fontIndex)
        assertEquals(TextAlign.LEFT, settings.textAlign)

        val justified = BookSettings(id = "bs-2", bookId = "book-1", textAlign = TextAlign.JUSTIFY)
        assertEquals(TextAlign.JUSTIFY, justified.textAlign)
    }

    @Test
    fun `layoutFingerprint reads all fields`() {
        val fp = LayoutFingerprint(
            fontSize = 16,
            lineHeight = 1.4f,
            fontWeight = 400,
            margin = 8,
            pageW = 1080,
            pageH = 2400,
        )
        assertEquals(1080, fp.pageW)
        assertEquals(2400, fp.pageH)
        assertEquals(LayoutMode.SINGLE, fp.mode)

        val doubleMode = fp.copy(mode = LayoutMode.DOUBLE)
        assertEquals(LayoutMode.DOUBLE, doubleMode.mode)
    }

    @Test
    fun `persona defaults apply`() {
        val persona = Persona(themeId = "sepia", fontIndex = 0, fontSize = 16, lineHeight = 1.4f)
        assertEquals(MotionPreference.SYSTEM, persona.motion)

        val reduced = persona.copy(motion = MotionPreference.REDUCED)
        assertEquals(MotionPreference.REDUCED, reduced.motion)
    }

    @Test
    fun `enum entries match the contract`() {
        assertEquals(
            listOf("TXT", "MARKDOWN", "EPUB", "UNKNOWN"),
            enumValues<BookFormat>().map { it.name },
        )
        assertEquals(
            listOf("PHYSICAL", "SIMPLE_FADE", "NONE"),
            enumValues<TurnStyle>().map { it.name },
        )
        assertEquals(
            listOf("HEADING", "PARAGRAPH", "DIALOGUE", "CAPTION", "IMAGE_PLACEHOLDER"),
            enumValues<BlockKind>().map { it.name },
        )
        assertEquals(
            listOf("LEFT", "JUSTIFY"),
            enumValues<TextAlign>().map { it.name },
        )
        assertEquals(
            listOf("SINGLE", "DOUBLE"),
            enumValues<LayoutMode>().map { it.name },
        )
        assertEquals(
            listOf("SYSTEM", "REDUCED", "OFF"),
            enumValues<MotionPreference>().map { it.name },
        )
    }
}