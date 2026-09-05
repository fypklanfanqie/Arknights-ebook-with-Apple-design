package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.BlockSearchHit
import com.lfq06.arknightsreader.database.FtsQueryBuilder

/**
 * In-book full-text search over the FTS4 index. Raw user input is never fed
 * to MATCH directly: [FtsQueryBuilder.toMatchQuery] quotes it as a phrase,
 * and the LIKE fallback covers CJK substring queries the simple tokenizer
 * cannot match.
 */
class BookSearcher(private val db: AppDatabase) {

    suspend fun search(rawQuery: String, limit: Int = 50): List<BlockSearchHit> {
        if (rawQuery.isBlank()) return emptyList()
        return db.bookSearchDao().search(
            matchQuery = FtsQueryBuilder.toMatchQuery(rawQuery),
            likeQuery = rawQuery.trim(),
            limit = limit,
        )
    }
}
