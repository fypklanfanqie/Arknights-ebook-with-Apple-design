package com.lfq06.arknightsreader.model

data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val charOffset: Int = 0,
    val title: String? = null,
    val snippet: String = "",
    val createdAt: Long = 0L,
)