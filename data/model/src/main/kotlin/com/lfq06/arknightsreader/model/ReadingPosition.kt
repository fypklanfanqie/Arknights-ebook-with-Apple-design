package com.lfq06.arknightsreader.model

data class ReadingPosition(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val charOffset: Int = 0,
    val progression: Double = 0.0,
    val updatedAt: Long = 0L,
)