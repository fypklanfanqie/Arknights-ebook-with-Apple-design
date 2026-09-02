package com.lfq06.arknightsreader.model

enum class LayoutMode {
    SINGLE,
    DOUBLE,
}

data class LayoutFingerprint(
    val fontSize: Int,
    val lineHeight: Float,
    val fontWeight: Int,
    val margin: Int,
    val pageW: Int,
    val pageH: Int,
    val mode: LayoutMode = LayoutMode.SINGLE,
)