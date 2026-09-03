package com.lfq06.arknightsreader.format.api

import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.Chapter
import com.lfq06.arknightsreader.model.ContentBlock
import com.lfq06.arknightsreader.model.ReadingCapabilities

/** Raised when a source cannot be parsed as this module's format. */
class ParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** cheap pre-parse sniff result. */
data class FormatProbeResult(
    val likelyFormat: BookFormat,
    /** Confidence 0..1 that [likelyFormat] is right. */
    val confidence: Double,
)

/**
 * A unified parser contract for one book format. Implementations convert a
 * source stream into ordered [Chapter]s of [com.lfq06.arknightsreader.model.ContentBlock]s
 * with stable ids — the reader, repository, and search layers never touch
 * format containers directly.
 */
interface FormatModule {
    fun probe(header: ByteArray, sizeBytes: Long): FormatProbeResult

    /**
     * Parses the whole source. [readBlock] supplies successive chunks; the
     * module owns decoding and chapter/block splitting. Block ids are
     * deterministic: "<bookId>-c<chapter>-b<index>".
     */
    fun parse(
        bookId: String,
        sizeBytes: Long,
        readBlock: (maxLength: Int) -> ByteArray?,
    ): ParsedBook

    fun capabilities(): ReadingCapabilities

    /** Releases any held resources (zip handles, buffers). */
    fun close()
}

/** Output of [FormatModule.parse]: ordered chapters plus their blocks. */
data class ParsedBook(
    val title: String?,
    val author: String?,
    val chapters: List<Chapter>,
    /** Blocks grouped by chapter id, in reading order. */
    val blocksByChapter: Map<String, List<ContentBlock>> = emptyMap(),
)

/** Shared parsing limits (defense-in-depth; import pipeline may tighten). */
object FormatLimits {
    /** 64 MiB default ceiling for one book source. */
    const val MAX_SOURCE_BYTES: Long = 64L * 1024 * 1024

    /** Per-block text ceiling: 1 MiB is far beyond any real paragraph. */
    const val MAX_BLOCK_CHARS: Int = 1024 * 1024

    /** Hard cap on chapters per book. */
    const val MAX_CHAPTERS: Int = 20_000

    /** Hard cap on blocks per book. */
    const val MAX_BLOCKS: Int = 500_000

    /** Read chunk size handed to [FormatModule.parse]. */
    const val CHUNK_BYTES: Int = 64 * 1024
}
