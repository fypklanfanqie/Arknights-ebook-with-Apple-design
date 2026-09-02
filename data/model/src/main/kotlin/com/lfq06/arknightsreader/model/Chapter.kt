package com.lfq06.arknightsreader.model

enum class BlockKind {
    HEADING,
    PARAGRAPH,
    DIALOGUE,
    CAPTION,
    IMAGE_PLACEHOLDER,
}

data class Chapter(
    val id: String,
    val bookId: String,
    val orderIndex: Int,
    val title: String,
    val spineId: String? = null,
    val href: String? = null,
)

data class ContentBlock(
    val id: String,
    val chapterId: String,
    val orderIndex: Int,
    val kind: BlockKind,
    val text: String,
    val imageRef: String? = null,
)