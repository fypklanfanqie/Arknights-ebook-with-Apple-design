package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.format.api.FormatLimits
import com.lfq06.arknightsreader.format.api.FormatModule
import com.lfq06.arknightsreader.format.api.FormatProbeResult
import com.lfq06.arknightsreader.format.api.ParseException
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.TurnStyle
import java.io.ByteArrayOutputStream

/**
 * Handwritten EPUB 2/3 parser, prose-oriented and security-hardened:
 * - SafeZip enforces entry/size limits, rejects zip-slip paths and the DRM
 *   marker, so encrypted books are rejected up front.
 * - EpubXml hard-disables DOCTYPE/XXE.
 * - XhtmlSanitizer reduces each spine document to whitelisted prose blocks.
 *
 * Chapter order follows the OPF spine; titles come from the document's first
 * heading (or its <title>), so no NCX/NAV traversal is required for prose.
 */
class EpubModule : FormatModule {

    private val manifestHrefs = HashSet<String>()

    override fun probe(header: ByteArray, sizeBytes: Long): FormatProbeResult {
        val isZip = header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        return FormatProbeResult(
            likelyFormat = if (isZip) BookFormat.EPUB else BookFormat.UNKNOWN,
            confidence = if (isZip) 0.9 else 0.0,
        )
    }

    override fun parse(
        bookId: String,
        sizeBytes: Long,
        readBlock: (Int) -> ByteArray?,
    ): ParsedBook {
        val whole = readWhole(readBlock)
        if (sizeBytes > FormatLimits.MAX_SOURCE_BYTES || whole.size > FormatLimits.MAX_SOURCE_BYTES) {
            throw ParseException("source exceeds the ${FormatLimits.MAX_SOURCE_BYTES} limit")
        }
        if (!isZipMagic(whole)) throw ParseException("epub file does not start with a zip signature")
        return parseEpub(bookId, whole)
    }

    private fun parseEpub(bookId: String, bytes: ByteArray): ParsedBook {
        val entries = SafeZip.readAll(bytes)
        manifestHrefs.clear()
        // Collect packaged resource hrefs (images/CSS) for the sanitizer.
        entries.forEach { e -> manifestHrefs.add(e.name) }

        val opfPath = OpfParser.containerOpfPath(entries)
        val pkg = OpfParser.parseOpf(entries, opfPath)

        val chapters = ArrayList<Chapter>(pkg.spineHrefs.size)
        val blocksByChapter = HashMap<String, MutableList<ContentBlock>>()

        pkg.spineHrefs.forEachIndexed { ci, href ->
            val entry = entries.firstOrNull { it.name == href }
                ?: return@forEachIndexed // missing spine item: skip, not fatal
            val chapterId = "$bookId-c$ci"
            val sanitized = XhtmlSanitizer.sanitize(
                String(entry.bytes, Charsets.UTF_8),
                manifestHrefs,
            )
            var title = sanitized.firstOrNull { it.kind == com.lfq06.arknightsreader.model.BlockKind.HEADING }?.text
            if (title.isNullOrBlank()) {
                // Fall back to the doc <title> if no in-body heading.
                title = "Chapter ${ci + 1}"
            }
            chapters.add(
                Chapter(
                    id = chapterId,
                    bookId = bookId,
                    orderIndex = ci,
                    title = title,
                    spineId = href,
                    href = href,
                ),
            )
            val list = blocksByChapter.getOrPut(chapterId) { ArrayList() }
            sanitized.forEachIndexed { bi, b ->
                list.add(
                    b.copy(
                        id = "$bookId-c$ci-b$bi",
                        chapterId = chapterId,
                        orderIndex = bi,
                    ),
                )
            }
        }

        if (chapters.isEmpty()) throw ParseException("epub spine produced no chapters")
        return ParsedBook(
            title = pkg.title,
            author = pkg.author,
            chapters = chapters,
            blocksByChapter = blocksByChapter,
        )
    }

    override fun capabilities(): ReadingCapabilities = ReadingCapabilities(
        reflow = true,
        font = true,
        background = true,
        search = true,
        annotate = true,
        turnStyles = listOf(TurnStyle.PHYSICAL, TurnStyle.SIMPLE_FADE, TurnStyle.NONE),
    )

    override fun close() { /* stateless */ }

    private fun readWhole(readBlock: (Int) -> ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val chunk = readBlock(FormatLimits.CHUNK_BYTES) ?: break
            out.write(chunk)
            if (out.size() > FormatLimits.MAX_SOURCE_BYTES) {
                throw ParseException("source exceeds the ${FormatLimits.MAX_SOURCE_BYTES} limit")
            }
        }
        return out.toByteArray()
    }

    private fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
}
