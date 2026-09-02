package com.lfq06.arknightsreader.model

enum class BookFormat {
    TXT,
    MARKDOWN,
    EPUB,
    UNKNOWN,
}

enum class TurnStyle {
    PHYSICAL,
    SIMPLE_FADE,
    NONE,
}

data class ReadingCapabilities(
    val reflow: Boolean = false,
    val font: Boolean = false,
    val background: Boolean = false,
    val search: Boolean = false,
    val annotate: Boolean = false,
    val turnStyles: List<TurnStyle> = emptyList(),
)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val source: String,
    val format: BookFormat = BookFormat.UNKNOWN,
    val formatVersion: Int = 1,
    val coverPath: String? = null,
    val addedAt: Long = 0L,
    val lastOpenedAt: Long? = null,
    val progressPct: Double = 0.0,
    val capabilities: ReadingCapabilities = ReadingCapabilities(),
)