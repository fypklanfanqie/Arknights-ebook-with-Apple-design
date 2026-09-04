package com.lfq06.arknightsreader.format.text

import com.lfq06.arknightsreader.format.api.FormatLimits
import com.lfq06.arknightsreader.format.api.ParseException
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.BookFormat
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncodingDetectorTest {

    @Test
    fun `detects utf-8 bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "文字".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, EncodingDetector.detect(bytes))
    }

    @Test
    fun `detects utf-16 le and be boms`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "文".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, EncodingDetector.detect(le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "文".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, EncodingDetector.detect(be))
    }

    @Test
    fun `detects gb18030 by leading bytes`() {
        // 中 in GB18030 is D6 D0; a Chinese document without BOM.
        val bytes = "第一章 测试".toByteArray(charset("GB18030"))
        assertEquals(charset("GB18030"), EncodingDetector.detect(bytes))
    }

    @Test
    fun `falls back to utf-8 for plain ascii`() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, EncodingDetector.detect(bytes))
    }

    @Test
    fun `empty input defaults to utf-8`() {
        assertEquals(Charsets.UTF_8, EncodingDetector.detect(ByteArray(0)))
    }
}

class TxtModuleTest {

    /** Feeds [text] encoded with [charset] through the module's chunked reader. */
    private fun parse(
        text: String,
        charset: Charset = Charsets.UTF_8,
        bookId: String = "b1",
        chunkSize: Int = FormatLimits.CHUNK_BYTES,
        module: AbstractTextModule = TxtModule(),
    ): ParsedBook {
        val bytes = text.toByteArray(charset)
        var offset = 0
        return module.parse(
            bookId = bookId,
            sizeBytes = bytes.size.toLong(),
            readBlock = { max ->
                if (offset >= bytes.size) null else {
                    val end = minOf(offset + chunkSize, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)
                    offset = end
                    chunk
                }
            },
        )
    }

    /** Blocks of chapter #[index], resolved through blocksByChapter. */
    private fun blocksOfBook(book: ParsedBook, index: Int) =
        book.blocksByChapter[book.chapters[index].id].orEmpty()

    @Test
    fun `probe reports txt with high confidence`() {
        val probe = TxtModule().probe("第一章\n正文".toByteArray(), sizeBytes = 20)
        assertEquals(BookFormat.TXT, probe.likelyFormat)
        assertTrue(probe.confidence > 0.5)
    }

    @Test
    fun `plain text without headings becomes one chapter`() {
        val book = parse("第一段。\n\n第二段。")
        assertEquals(1, book.chapters.size)
        val blocks = blocksOfBook(book, 0)
        assertEquals(listOf("第一段。", "第二段。"), blocks.map { it.text })
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), blocks.map { it.kind })
    }

    @Test
    fun `cjk chapter headings split chapters in order`() {
        val text = "第一章 开端\n甲之内容。\n\n第二章 转折\n乙之内容。\n\n第三章 终局\n丙之内容。"
        val book = parse(text)
        assertEquals(listOf("第一章 开端", "第二章 转折", "第三章 终局"), book.chapters.map { it.title })
        assertEquals(
            listOf(listOf("甲之内容。"), listOf("乙之内容。"), listOf("丙之内容。")),
            book.chapters.indices.map { i -> blocksOfBook(book, i).map { it.text } },
        )
    }

    @Test
    fun `english numbered headings split chapters`() {
        val text = "Chapter 1 The Beginning\nAlpha.\n\nChapter 2 The Middle\nBeta."
        val book = parse(text)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].title.startsWith("Chapter 1"))
    }

    @Test
    fun `juan and hui variants are recognized`() {
        val text = "第一卷 起\nA\n\n第二回 承\nB"
        val book = parse(text)
        assertEquals(2, book.chapters.size)
    }

    @Test
    fun `preamble before first heading becomes chapter zero`() {
        val text = "楔子：故事开始。\n\n第一章 正题\n正文。"
        val book = parse(text)
        assertEquals(2, book.chapters.size)
        assertEquals(listOf("楔子：故事开始。"), blocksOfBook(book, 0).map { it.text })
        assertEquals("第一章 正题", book.chapters[1].title)
    }

    @Test
    fun `block ids are deterministic across runs`() {
        val text = "第一章\n甲。\n\n第二章\n乙。"
        val first = parse(text, bookId = "b1")
        val second = parse(text, bookId = "b1")
        assertEquals(
            first.chapters.indices.flatMap { i -> blocksOfBook(first, i) }.map { it.id },
            second.chapters.indices.flatMap { i -> blocksOfBook(second, i) }.map { it.id },
        )
        assertTrue(blocksOfBook(first, 0)[0].id.startsWith("b1-"))
    }

    @Test
    fun `gb18030 input decodes correctly`() {
        val text = "第一章 测试\n中文内容验证。"
        val book = parse(text, charset = charset("GB18030"))
        assertEquals(listOf("中文内容验证。"), blocksOfBook(book, 0).map { it.text })
    }

    @Test
    fun `oversized source is rejected`() {
        val module = TxtModule()
        assertFailsWith<ParseException> {
            module.parse(
                bookId = "b1",
                sizeBytes = FormatLimits.MAX_SOURCE_BYTES + 1,
                readBlock = { null },
            )
        }
    }

    @Test
    fun `utf-16 le multi-line file parses end to end`() {
        val text = "第一章 开端\n甲之内容。\n\n第二章 转折\n乙之内容。"
        // UTF-16 without a BOM is indistinguishable from an arbitrary byte
        // stream (CJK LE/BE bytes land in ASCII range), so files carry a BOM.
        val withBom = "﻿" + text
        val book = parse(withBom, charset = Charsets.UTF_16LE)
        assertEquals(listOf("第一章 开端", "第二章 转折"), book.chapters.map { it.title })
        assertEquals(
            listOf(listOf("甲之内容。"), listOf("乙之内容。")),
            book.chapters.indices.map { i -> blocksOfBook(book, i).map { it.text } },
        )
    }

    @Test
    fun `utf-16 be with bom parses and strips feff`() {
        val text = "第一章 标题\n正文内容。"
        val withBom = "﻿" + text
        val book = parse(withBom, charset = Charsets.UTF_16BE)
        assertEquals(listOf("第一章 标题"), book.chapters.map { it.title })
        // The heading must not be poisoned by a leading U+FEFF.
        assertTrue(book.chapters[0].title.startsWith("第一章"))
        assertEquals(listOf("正文内容。"), blocksOfBook(book, 0).map { it.text })
    }

    @Test
    fun `tiny chunks reproduce identical results (line reassembly)`() {
        // 3-byte chunks force multi-byte CJK chars and CRLF pairs to straddle
        // chunk boundaries — the exact path the LineStream bug broke.
        val text = "第一章 开端\n甲之内容。\n\n第二章 转折\n乙之内容。"
        val tiny = parse(text, chunkSize = 3)
        val whole = parse(text)
        assertEquals(whole.chapters.map { it.title }, tiny.chapters.map { it.title })
        assertEquals(
            whole.chapters.indices.map { i -> blocksOfBook(whole, i).map { it.text } },
            tiny.chapters.indices.map { i -> blocksOfBook(tiny, i).map { it.text } },
        )
    }

    @Test
    fun `cr-only line endings still split lines`() {
        val book = parse("第一章 A\n甲。\r第二章 B\r乙。")
        assertEquals(2, book.chapters.size)
        assertEquals(listOf("甲。"), blocksOfBook(book, 0).map { it.text })
        assertEquals(listOf("乙。"), blocksOfBook(book, 1).map { it.text })
    }

    @Test
    fun `utf-8 cjk is not misdetected as gb18030`() {
        // Review I-2 repro: an even-count CJK run once fooled the GB pairing
        // walk into GB18030-decoding UTF-8 text (silent mojibake). Strict
        // UTF-8 validation now runs first, so the text must decode exactly.
        val text = "中文\n中文\n第一章 检查\n内容验证完成。"
        val book = parse(text, charset = Charsets.UTF_8)
        // Preamble lines become chapter 0 (documented preamble semantics).
        assertEquals(listOf("未分章", "第一章 检查"), book.chapters.map { it.title })
        assertEquals(
            listOf("中文", "中文", "内容验证完成。"),
            book.chapters.flatMap { c -> book.blocksByChapter[c.id].orEmpty().map { it.text } },
        )
    }

    @Test
    fun `undecodable garbage is rejected`() {
        // 0x81 followed by 0x00: the 0x81 lead breaks the GB18030 pairing rule
        // (trail byte 0x00 is not in 0x40..0xFE), so detection falls back to
        // UTF-8, where the lone 0x81 continuation byte is malformed.
        val bytes = ByteArray(64) { if (it % 2 == 0) 0x81.toByte() else 0x00 }
        var offset = 0
        val module = TxtModule()
        assertFailsWith<ParseException> {
            module.parse(
                bookId = "b1",
                sizeBytes = bytes.size.toLong(),
                readBlock = { max ->
                    if (offset >= bytes.size) null else {
                        val end = minOf(offset + max, bytes.size)
                        val chunk = bytes.copyOfRange(offset, end)
                        offset = end
                        chunk
                    }
                },
            )
        }
    }
}

/** Blocks of chapter #[index] in [book], resolved through blocksByChapter. */
private fun blocksOf(book: ParsedBook, index: Int) =
    book.blocksByChapter[book.chapters[index].id].orEmpty()

class MdModuleTest {

    private fun parse(text: String, bookId: String = "b1"): ParsedBook {
        val bytes = text.toByteArray()
        var offset = 0
        return MdModule().parse(
            bookId = bookId,
            sizeBytes = bytes.size.toLong(),
            readBlock = { max ->
                if (offset >= bytes.size) null else {
                    val end = minOf(offset + max, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)
                    offset = end
                    chunk
                }
            },
        )
    }

    @Test
    fun `h1 and h2 split chapters`() {
        val text = "# 卷一\n\n## 第一章 起\n段落甲。\n\n## 第二章 承\n段落乙。"
        val book = parse(text)
        assertEquals(listOf("卷一", "第一章 起", "第二章 承"), book.chapters.map { it.title })
        // 卷一 has no body; the two h2 chapters carry the paragraphs.
        val titled = book.chapters.mapIndexed { i, c -> c.title to blocksOf(book, i).map { it.text } }
        assertEquals(
            listOf("第一章 起" to listOf("段落甲。"), "第二章 承" to listOf("段落乙。")),
            titled.filter { it.second.isNotEmpty() },
        )
    }

    @Test
    fun `paragraphs quotes and rules map to block kinds`() {
        val text = "## 章\n\n普通段落。\n\n> 引用内容。\n\n---\n"
        val book = parse(text)
        val kinds = blocksOf(book, 0).map { it.kind }
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.CAPTION, BlockKind.PARAGRAPH), kinds)
    }

    @Test
    fun `images become placeholders with refs`() {
        val text = "## 章\n\n![描述](images/pic.png)\n"
        val book = parse(text)
        val block = blocksOf(book, 0)[0]
        assertEquals(BlockKind.IMAGE_PLACEHOLDER, block.kind)
        assertEquals("images/pic.png", block.imageRef)
    }

    @Test
    fun `inline markdown markers are stripped from text`() {
        val text = "## 章\n\n这是**粗体**和*斜体*以及`代码`。"
        val book = parse(text)
        assertEquals("这是粗体和斜体以及代码。", blocksOf(book, 0)[0].text)
    }
}
