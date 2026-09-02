package com.lfq06.arknightsreader.model

data class Locator(
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val charOffset: Int,
    val progression: Double,
)