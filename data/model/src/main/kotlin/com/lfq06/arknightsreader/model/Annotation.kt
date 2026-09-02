package com.lfq06.arknightsreader.model

data class Annotation(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quote: String,
    val color: String? = null,
    val note: String? = null,
    val anchoredVersion: Int? = null,
    val orphaned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)