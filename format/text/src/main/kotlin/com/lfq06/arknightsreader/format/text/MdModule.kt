package com.lfq06.arknightsreader.format.text

import com.lfq06.arknightsreader.format.api.FormatLimits
import com.lfq06.arknightsreader.format.api.FormatProbeResult
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock

/**
 * Markdown parser, prose-oriented: ATX headings (#..######) split chapters
 * (h1/h2 = chapter boundary, deeper levels demoted to heading blocks),
 * blockquotes map to CAPTION, thematic breaks to separators, images to
 * placeholders. Inline markers (bold/italic/code/links) are stripped.
 */
class MdModule : AbstractTextModule() {

    override fun probe(header: ByteArray, sizeBytes: Long): FormatProbeResult = FormatProbeResult(
        likelyFormat = BookFormat.MARKDOWN,
        confidence = 0.4, // weak signal: real detection happens in the importer
    )

    override fun doParse(bookId: String, lines: LineStream): ParsedBook {
        blockCounter = 0

        data class Draft(
            val title: String?,
            val lines: List<String>,
            val kinds: List<BlockKind>,
            val imageRefs: List<String?>,
        )

        val drafts = ArrayList<Draft>()
        var currentTitle: String? = null
        val currentLines = ArrayList<String>()
        val currentKinds = ArrayList<BlockKind>()
        val currentImages = ArrayList<String?>()

        fun flush() {
            if (currentLines.isEmpty() && currentTitle == null && drafts.isEmpty()) return
            drafts.add(Draft(currentTitle, currentLines.toList(), currentKinds.toList(), currentImages.toList()))
            currentLines.clear()
            currentKinds.clear()
            currentImages.clear()
        }

        while (true) {
            val raw = lines.nextLine() ?: break
            val line = raw.trimEnd()
            val heading = matchAtxHeading(line)
            if (heading != null) {
                val (level, text) = heading
                if (level <= 2) {
                    flush()
                    currentTitle = text
                } else {
                    currentLines.add(text)
                    currentKinds.add(BlockKind.HEADING)
                    currentImages.add(null)
                }
                continue
            }
            if (line.isBlank()) continue
            if (THEMATIC_BREAK.matches(line)) {
                currentLines.add("")
                currentKinds.add(BlockKind.PARAGRAPH)
                currentImages.add(null)
                continue
            }
            val quote = BLOCK_QUOTE.matchEntire(line)
            if (quote != null) {
                val content = inline(quote.groupValues[1])
                if (content.isNotBlank()) {
                    currentLines.add(content)
                    currentKinds.add(BlockKind.CAPTION)
                    currentImages.add(null)
                }
                continue
            }
            val image = IMAGE_ONLY.matchEntire(line)
            if (image != null) {
                currentLines.add(image.groupValues[1])
                currentKinds.add(BlockKind.IMAGE_PLACEHOLDER)
                currentImages.add(image.groupValues[2])
                continue
            }
            val text = inline(line)
            if (text.isNotBlank()) {
                currentLines.add(text)
                currentKinds.add(BlockKind.PARAGRAPH)
                currentImages.add(null)
            }
            if (blockCounter > FormatLimits.MAX_BLOCKS) {
                throw com.lfq06.arknightsreader.format.api.ParseException("block count exceeds ${FormatLimits.MAX_BLOCKS}")
            }
        }
        flush()

        if (drafts.isEmpty()) {
            drafts.add(Draft(null, emptyList(), emptyList(), emptyList()))
        }

        val chapters = ArrayList<Chapter>(drafts.size)
        drafts.forEachIndexed { ci, draft ->
            chapters.add(
                Chapter(
                    id = chapterId(bookId, ci),
                    bookId = bookId,
                    orderIndex = ci,
                    title = draft.title ?: "未分章",
                    spineId = null,
                    href = null,
                ),
            )
        }

        drafts.forEachIndexed { ci, draft ->
            val cid = chapterId(bookId, ci)
            var order = 0
            for ((i, line) in draft.lines.withIndex()) {
                val block = ContentBlock(
                    id = nextBlockId(bookId, ci),
                    chapterId = cid,
                    orderIndex = order++,
                    kind = draft.kinds[i],
                    text = line,
                    imageRef = draft.imageRefs[i],
                )
                blocksByChapter.getOrPut(cid) { ArrayList() }.add(block)
            }
        }

        return ParsedBook(
            title = null,
            author = null,
            chapters = chapters,
            blocksByChapter = blocksByChapter,
        )
    }

    private val blocksByChapter = HashMap<String, MutableList<ContentBlock>>()

    private fun matchAtxHeading(line: String): Pair<Int, String>? {
        val m = ATX_HEADING.matchEntire(line.trimEnd()) ?: return null
        val level = m.groupValues[1].length
        val text = inline(m.groupValues[2].trim())
        return level to text
    }

    companion object {
        private val ATX_HEADING = Regex("^(#{1,6})\\s+(.*)$")
        private val THEMATIC_BREAK = Regex("^ {0,3}((\\-\\s*){3,}|(\\*\\s*){3,}|(_\\s*){3,})$")
        private val BLOCK_QUOTE = Regex("^ {0,3}>\\s?(.*)$")
        private val IMAGE_ONLY = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)\\)\\s*$")

        /** Strips inline markdown markers; keeps the human-readable text. */
        fun inline(text: String): String = text
            .replace(Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)")) { m -> if (m.groupValues[1].isBlank()) "" else m.groupValues[1] }
            .replace(Regex("\\[([^\\]]*)]\\(([^)\\s]+)\\)")) { m -> m.groupValues[1] }
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("_([^_]+)_"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("~~([^~]+)~~"), "$1")
            .trim()
    }
}
