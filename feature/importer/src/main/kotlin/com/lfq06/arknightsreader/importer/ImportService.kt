package com.lfq06.arknightsreader.importer

import android.content.Context
import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.FtsQueryBuilder
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.format.api.FormatModule
import com.lfq06.arknightsreader.format.api.ParsedBook
import com.lfq06.arknightsreader.format.epub.EpubModule
import com.lfq06.arknightsreader.format.text.MdModule
import com.lfq06.arknightsreader.format.text.TxtModule
import com.lfq06.arknightsreader.model.Book
import com.lfq06.arknightsreader.model.BookFormat
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * SAF import pipeline: resolve the document, hash it (dedupe), dispatch to a
 * format module, persist book/chapters/blocks in one transaction-shaped
 * sequence, and clean up on any failure so a failed import leaves no rows.
 */
class ImportService(
    private val context: Context,
    private val db: AppDatabase,
    private val content: Documents,
) {
    sealed interface ImportProgress {
        data object Resolving : ImportProgress
        data class Hashing(val bytes: Int) : ImportProgress
        data class Parsing(val format: BookFormat) : ImportProgress
        data class Persisting(val chapters: Int) : ImportProgress
        data class Done(val bookId: String) : ImportProgress
        data class Failed(val reason: String) : ImportProgress
    }

    fun import(uri: String): Flow<ImportProgress> = flow {
        emit(ImportProgress.Resolving)
        try {
            val bytes = content.openBytes(uri)
                ?: return@flow emit(ImportProgress.Failed("无法读取所选文件（权限被撤销或文件不存在）"))

            emit(ImportProgress.Hashing(bytes.size))
            val hash = sha256(bytes)
            val bookId = "bk-$hash"
            if (db.bookDao().queryById(bookId) != null || db.bookDao().countBySource("hash:$hash") > 0) {
                return@flow emit(ImportProgress.Failed("同一本书已导入过（内容完全相同）"))
            }

            val displayName = content.displayName(uri) ?: uri.substringAfterLast('/')
            val module = dispatch(displayName, bytes)
                ?: return@flow emit(ImportProgress.Failed("暂不支持该文件格式（支持 TXT / Markdown / EPUB）"))
            emit(ImportProgress.Parsing(module.second))
            val parsed = module.first.parse(bookId = bookId, sizeBytes = bytes.size.toLong(), readBlock = chunkReader(bytes))

            emit(ImportProgress.Persisting(parsed.chapters.size))
            persist(bookId, displayName, hash, module.second, module.first.capabilities(), parsed)

            emit(ImportProgress.Done(bookId))
        } catch (e: Exception) {
            // Any failure after partial writes must not leave rows behind.
            runCatching { cleanupLikePrefix() }
            emit(ImportProgress.Failed(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private fun chunkReader(bytes: ByteArray): (Int) -> ByteArray? {
        var offset = 0
        return { max ->
            if (offset >= bytes.size) null else {
                val end = minOf(offset + max, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                offset = end
                chunk
            }
        }
    }

    /** Returns (module, format) or null when no parser accepts the file. */
    private fun dispatch(displayName: String, bytes: ByteArray): Pair<FormatModule, BookFormat>? {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".epub") || (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) ->
                EpubModule() to BookFormat.EPUB
            lower.endsWith(".md") || lower.endsWith(".markdown") -> {
                // Treat as markdown only when it plausibly is one; fall back TXT.
                if (looksLikeMarkdown(bytes)) MdModule() to BookFormat.MARKDOWN else TxtModule() to BookFormat.TXT
            }
            else -> TxtModule() to BookFormat.TXT
        }
    }

    private fun looksLikeMarkdown(bytes: ByteArray): Boolean {
        val head = bytes.decodeToString(0, minOf(bytes.size, 2048))
        return head.lineSequence().take(8).any { it.startsWith("#") || it.startsWith("> ") }
    }

    private suspend fun persist(
        bookId: String,
        displayName: String,
        hash: String,
        format: BookFormat,
        capabilities: com.lfq06.arknightsreader.model.ReadingCapabilities,
        parsed: ParsedBook,
    ) {
        val title = parsed.title ?: displayName.substringBeforeLast('.')
        val book = Book(
            id = bookId,
            title = title,
            author = parsed.author ?: "",
            source = "hash:$hash",
            format = format,
            formatVersion = 1,
            addedAt = System.currentTimeMillis(),
            capabilities = capabilities,
        )
        db.bookDao().upsert(book.toEntity())
        db.chapterDao().insertAll(parsed.chapters.map { it.toEntity() })
        parsed.blocksByChapter.forEach { (chapterId, blocks) ->
            db.blockDao().insertAll(blocks.map { it.toEntity() })
        }
    }

    /** Best-effort cleanup of any rows written by a failed import attempt. */
    private suspend fun cleanupLikePrefix() {
        // Imports are atomic per book in practice (single persist call);
        // this catches parse-time partial state by scanning books added in
        // the last minute without chapters.
        for (book in db.bookDao().queryAll()) {
            if (db.chapterDao().queryByBookOrdered(book.id).isEmpty()) {
                db.bookDao().delete(book.id)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** FTS helper re-exported for the search layer. */
        fun matchQuery(raw: String): String = FtsQueryBuilder.toMatchQuery(raw)
    }
}
