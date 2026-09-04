package com.lfq06.arknightsreader.reader

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Fingerprint-keyed pagination cache. Entries survive configuration changes
 * within a reader session; a new [LayoutSpec.fingerprint] never collides
 * with an old one.
 */
class PageCache {
    private data class Key(
        val width: Int,
        val height: Int,
        val margins: Int,
        val textSize: Int,
        val lineHeight: Float,
        val mode: String,
        val contentHash: Int,
    )

    private val entries = ConcurrentHashMap<Key, PageMap>()

    fun getOrCompute(
        context: Context,
        blocks: List<com.lfq06.arknightsreader.model.ContentBlock>,
        spec: LayoutSpec,
        compute: (List<com.lfq06.arknightsreader.model.ContentBlock>, LayoutSpec) -> PageMap,
    ): PageMap {
        val key = Key(
            width = spec.pageWidthPx,
            height = spec.pageHeightPx,
            margins = spec.marginsPx,
            textSize = spec.textSizePx,
            lineHeight = spec.lineHeightFactor,
            mode = spec.mode.name,
            contentHash = blocks.hashCode(),
        )
        return entries.getOrPut(key) { compute(blocks, spec) }
    }

    fun clear() = entries.clear()
}
