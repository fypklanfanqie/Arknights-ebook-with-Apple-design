package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toEntity
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.model.Annotation

/**
 * Text-anchored annotation logic: reflow-stable re-anchoring (quote +
 * context) and per-page highlight lookup. Orphaned notes are never silently
 * moved — they surface in the UI for manual repair.
 */
class AnnotationAnchor(private val db: AppDatabase) {

    /**
     * Re-anchors [annotation] against [currentText]. Strategy:
     * 1. The exact stored range still contains the quote -> keep offsets.
     * 2. Otherwise locate the quote string in the block (offsets shift on
     *    reflow/prefix edits) -> update offsets + anchoredVersion.
     * 3. Otherwise mark orphaned; the UI shows a repair prompt.
     */
    suspend fun reanchor(annotation: Annotation, currentText: String, version: Int): Annotation {
        val inRange = annotation.startOffset in 0..currentText.length &&
            annotation.endOffset <= currentText.length &&
            currentText.substring(annotation.startOffset, annotation.endOffset) == annotation.quote
        if (inRange) return annotation

        val idx = currentText.indexOf(annotation.quote)
        val updated = if (idx >= 0) {
            annotation.copy(
                startOffset = idx,
                endOffset = idx + annotation.quote.length,
                anchoredVersion = version,
                orphaned = false,
            )
        } else {
            annotation.copy(orphaned = true, anchoredVersion = version)
        }
        db.annotationDao().insert(updated.toEntity())
        return updated
    }

    /** Highlights overlapping the page's [startOffset, endOffset) range. */
    suspend fun highlightsFor(chapterId: String, blockId: String, startOffset: Int, endOffset: Int): List<Annotation> =
        db.annotationDao().queryByChapterBlockAnchor(chapterId, blockId, startOffset, endOffset)
            .map { it.toModel() }
            .filter { !it.orphaned }
}
