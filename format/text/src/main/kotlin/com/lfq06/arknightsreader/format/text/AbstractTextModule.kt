package com.lfq06.arknightsreader.format.text

import com.lfq06.arknightsreader.format.api.FormatLimits
import com.lfq06.arknightsreader.format.api.FormatModule
import com.lfq06.arknightsreader.format.api.FormatProbeResult
import com.lfq06.arknightsreader.format.api.ParseException
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.TurnStyle
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Shared plumbing for line-oriented text formats: chunked decoding with
 * encoding detection and line assembly across chunk boundaries. Subclasses
 * turn lines into chapters/blocks and own their id scheme via [blockCounter].
 */
abstract class AbstractTextModule : FormatModule {

    /**
     * Chunked, encoding-aware line reader over the import byte stream.
     *
     * UTF-16 sources are decoded whole-chunk with a char-level line split
     * (byte scanning would cut 16-bit units in half); byte-oriented encodings
     * use a single-byte LF/CR scan with cross-chunk carry assembly.
     */
    protected class LineStream(readBlock: (Int) -> ByteArray?) {
        private val charset: Charset
        private val utf16: Boolean
        private val chunks = ArrayDeque<ByteArray>()
        private var carry = ByteArray(0)
        private var charCarry = StringBuilder()
        private var done = false
        private val reader: (Int) -> ByteArray?
        private var firstLine = true

        init {
            // Peek the first chunk to sniff the encoding before decoding.
            val first = readBlock(FormatLimits.CHUNK_BYTES)
            if (first == null) {
                charset = Charsets.UTF_8
                utf16 = false
                done = true
            } else {
                charset = EncodingDetector.detect(first)
                utf16 = charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
                chunks.addLast(first)
            }
            this.reader = readBlock
        }

        val encodingName: String get() = charset.name()

        fun nextLine(): String? = if (utf16) nextLineUtf16() else nextLineBytes()

        // ---- UTF-16 path: decode whole chunks, split lines per char unit ----

        // Pending odd byte carried between chunks so 16-bit units stay aligned.
        private var pendingByte = ByteArray(0)

        private fun nextLineUtf16(): String? {
            while (true) {
                val s = charCarry.toString()
                val nl = s.indexOfAny(charArrayOf('\n', '\r'))
                if (nl >= 0) {
                    val line = s.substring(0, nl)
                    var end = nl + 1
                    // CRLF spans both chars.
                    if (s[nl] == '\r' && end < s.length && s[end] == '\n') end += 1
                    charCarry = StringBuilder(s.substring(end))
                    return stripBom(line)
                }
                if (done) {
                    return if (charCarry.isEmpty()) null else {
                        val rest = charCarry.toString()
                        charCarry = StringBuilder()
                        stripBom(rest)
                    }
                }
                val next = if (chunks.isNotEmpty()) chunks.removeFirst() else reader(FormatLimits.CHUNK_BYTES)
                if (next == null) {
                    // A dangling high byte at EOF means a truncated stream.
                    if (pendingByte.isNotEmpty()) {
                        throw ParseException("truncated UTF-16 stream: dangling high byte")
                    }
                    done = true
                    continue
                }
                // Pair any pending high byte with the new chunk so 16-bit
                // units stay aligned across chunk boundaries.
                val full = if (pendingByte.isEmpty()) next else pendingByte + next
                pendingByte = ByteArray(0)
                val usable = if (full.size % 2 == 0) full else {
                    pendingByte = byteArrayOf(full[full.size - 1])
                    full.copyOfRange(0, full.size - 1)
                }
                charCarry.append(decode(usable))
                if (charCarry.length > FormatLimits.MAX_BLOCK_CHARS) {
                    throw ParseException("unterminated line exceeds sanity limit")
                }
            }
        }

        // ---- byte-oriented path (UTF-8 / GB18030) ----

        private fun nextLineBytes(): String? {
            while (true) {
                val nl = indexOfNewline(carry)
                if (nl >= 0) {
                    val (line, skip) = splitLine(carry, nl)
                    carry = carry.copyOfRange(nl + skip, carry.size)
                    return stripBom(decode(line))
                }
                if (done) {
                    return if (carry.isEmpty()) null else {
                        val rest = carry
                        carry = ByteArray(0)
                        stripBom(decode(rest))
                    }
                }
                // Drain the sniffed chunk queue before pulling more bytes.
                val next = if (chunks.isNotEmpty()) chunks.removeFirst() else reader(FormatLimits.CHUNK_BYTES)
                if (next == null) {
                    done = true
                    continue
                }
                carry = if (carry.isEmpty()) next else carry + next
                if (carry.size > FormatLimits.MAX_BLOCK_CHARS * 4) {
                    throw ParseException("unterminated line exceeds sanity limit")
                }
            }
        }

        /**
         * Strips a leading U+FEFF from the first decoded line. UTF-8 BOM is
         * removed at the byte level in [splitLine]; UTF-16 decoders keep the
         * BOM as a character, so drop it here once.
         */
        private fun stripBom(line: String): String {
            if (!firstLine) return line
            val cleaned = line.removePrefix("﻿")
            firstLine = false
            return cleaned
        }

        private fun decode(bytes: ByteArray): String = try {
            // REPORT (not the lenient String()) so undecodable bytes surface
            // as a user-visible parse error instead of silent U+FFFD runs.
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            throw ParseException("cannot decode text as ${charset.name()}", e)
        }

        companion object {
            /** Index of the next LF or CR (either may end a line). */
            fun indexOfNewline(bytes: ByteArray): Int {
                for (i in bytes.indices) {
                    val b = bytes[i]
                    if (b == 0x0A.toByte() || b == 0x0D.toByte()) return i
                }
                return -1
            }

            /** Line bytes plus how many terminator bytes to skip (CRLF = 2). */
            fun splitLine(bytes: ByteArray, nl: Int): Pair<ByteArray, Int> {
                val isCr = bytes[nl] == 0x0D.toByte()
                val skip = if (isCr && nl + 1 < bytes.size && bytes[nl + 1] == 0x0A.toByte()) 2 else 1
                return bytes.copyOfRange(0, nl) to skip
            }
        }
    }

    protected var blockCounter = 0

    protected fun nextBlockId(bookId: String, chapterIndex: Int): String =
        "$bookId-c$chapterIndex-b${blockCounter++}"

    protected fun chapterId(bookId: String, chapterIndex: Int): String = "$bookId-c$chapterIndex"

    final override fun parse(
        bookId: String,
        sizeBytes: Long,
        readBlock: (Int) -> ByteArray?,
    ): ParsedBook {
        if (sizeBytes > FormatLimits.MAX_SOURCE_BYTES) {
            throw ParseException("source of $sizeBytes bytes exceeds the ${FormatLimits.MAX_SOURCE_BYTES} limit")
        }
        blockCounter = 0
        return doParse(bookId, LineStream(readBlock))
    }

    protected abstract fun doParse(bookId: String, lines: LineStream): ParsedBook

    override fun capabilities(): ReadingCapabilities = ReadingCapabilities(
        reflow = true,
        font = true,
        background = true,
        search = true,
        annotate = true,
        turnStyles = listOf(TurnStyle.PHYSICAL, TurnStyle.SIMPLE_FADE, TurnStyle.NONE),
    )

    override fun close() { /* stateless */ }
}
