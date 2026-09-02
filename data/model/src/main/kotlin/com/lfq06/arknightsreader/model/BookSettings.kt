package com.lfq06.arknightsreader.model

enum class TextAlign {
    LEFT,
    JUSTIFY,
}

data class BookSettings(
    val id: String,
    val bookId: String,
    val fontSize: Int = 16,
    val lineHeight: Float = 1.4f,
    val fontWeight: Int = 400,
    val margin: Int = 0,
    val fontIndex: Int = 0,
    val textAlign: TextAlign = TextAlign.LEFT,
)