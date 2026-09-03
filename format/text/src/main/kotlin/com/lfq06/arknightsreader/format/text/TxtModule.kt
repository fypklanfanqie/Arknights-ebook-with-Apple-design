package com.lfq06.arknightsreader.format.text

import com.lfq06.arknightsreader.format.api.FormatLimits
import com.lfq06.arknightsreader.format.api.FormatProbeResult
import com.lfq06.arknightsreader.format.api.ParseException
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock

/**
 * Chapter-title recognition shared by the TXT parser. Covers the common
 * Chinese novel conventions (第X章/回/卷/节 with CJK or Arabic numerals,
 * optional 序章/楔子/终章/番外) and English "Chapter N" headings.
 */
object ChapterSplitter {
    private val CJK_NUMBERED = Regex(
        "^\\s*第\\s*[0-9零一二三四五六七八九十百千万两]+\\s*[章回卷节集部篇][\\s:：、．.\\-—]*(.*)$",
    )

    /** Marker words (楔子 etc.) are headings only when they stand alone. */
    private val CJK_MARKER = Regex("^\\s*(序章|楔子|前言|终章|尾声|番外)\\s*$")

    private val EN_HEADING = Regex("^\\s*Chapter\\s+\\d+([\\s:：.\\-—]*(.*))?$", RegexOption.IGNORE_CASE)

    /** Returns the heading title if [line] is a chapter heading, else null. */
    fun matchHeading(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 60) return null
        CJK_MARKER.matchEntire(trimmed)?.let { return trimmed }
        CJK_NUMBERED.matchEntire(trimmed)?.let { return trimmed }
        EN_HEADING.matchEntire(trimmed)?.let { return trimmed }
        return null
    }
}

/**
 * Plain-text parser: encoding detection, CJK/English chapter-heading
 * recognition, blank-line paragraph separation, and deterministic block ids.
 * Files without any heading become a single chapter titled 未分章.
 */
class TxtModule : AbstractTextModule() {

    override fun probe(header: ByteArray, sizeBytes: Long): FormatProbeResult {
        // TXT is the fallback format; a ZIP magic marks EPUB/CBZ instead.
        val looksLikeZip = header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        return FormatProbeResult(
            likelyFormat = BookFormat.TXT,
            confidence = if (looksLikeZip) 0.1 else 0.8,
        )
    }

    override fun doParse(bookId: String, lines: LineStream): ParsedBook {
        blockCounter = 0

        data class Draft(val title: String?, val lines: List<String>)

        val drafts = ArrayList<Draft>()
        var current = ArrayList<String>()
        var currentTitle: String? = null

        fun flush() {
            val body = current.filter { it.isNotBlank() }
            if (body.isEmpty() && currentTitle == null && drafts.isEmpty()) return
            drafts.add(Draft(currentTitle, body))
            current = ArrayList()
        }

        while (true) {
            val line = lines.nextLine() ?: break
            val heading = ChapterSplitter.matchHeading(line)
            if (heading != null) {
                flush()
                currentTitle = heading
            } else if (line.isBlank()) {
                if (current.isNotEmpty() && current.last().isNotBlank()) current.add("")
            } else {
                current.add(line.trim())
            }
        }
        flush()

        if (drafts.isEmpty()) {
            drafts.add(Draft(null, emptyList()))
        }

        val chapters = ArrayList<Chapter>(drafts.size)
        drafts.forEachIndexed { ci, draft ->
            val chapter = Chapter(
                id = chapterId(bookId, ci),
                bookId = bookId,
                orderIndex = ci,
                title = draft.title ?: "未分章",
                spineId = null,
                href = null,
            )
            chapters.add(chapter)
        }

        // Blocks must be created after all chapter ids exist; the counter runs
        // in reading order so ids are deterministic across runs.
        val blocksByChapter = HashMap<String, MutableList<ContentBlock>>()
        drafts.forEachIndexed { ci, draft ->
            val cid = chapterId(bookId, ci)
            val list = blocksByChapter.getOrPut(cid) { ArrayList() }
            var order = 0
            for (line in draft.lines) {
                list.add(
                    ContentBlock(
                        id = nextBlockId(bookId, ci),
                        chapterId = cid,
                        orderIndex = order++,
                        kind = BlockKind.PARAGRAPH,
                        text = line,
                        imageRef = null,
                    ),
                )
            }
            if (blockCounter > FormatLimits.MAX_BLOCKS) {
                throw ParseException("block count exceeds ${FormatLimits.MAX_BLOCKS}")
            }
        }
        return ParsedBook(
            title = null,
            author = null,
            chapters = chapters,
            blocksByChapter = blocksByChapter,
        )
    }
}
