package com.lfq06.arknightsreader.reader

import com.lfq06.arknightsreader.model.LayoutFingerprint
import com.lfq06.arknightsreader.model.LayoutMode

/** One pagination layout configuration; part of the cache key. */
data class LayoutSpec(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val marginsPx: Int,
    val textSizePx: Int,
    val lineHeightFactor: Float,
    val mode: LayoutMode,
) {
    /** Fingerprint of everything that can change pagination results. */
    fun fingerprint() = LayoutFingerprint(
        fontSize = textSizePx,
        lineHeight = lineHeightFactor,
        fontWeight = 400,
        margin = marginsPx,
        pageW = pageWidthPx / if (mode == LayoutMode.DOUBLE) 2 else 1,
        pageH = pageHeightPx,
        mode = mode,
    )
}
