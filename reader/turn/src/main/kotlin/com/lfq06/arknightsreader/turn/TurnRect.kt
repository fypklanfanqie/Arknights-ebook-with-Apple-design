package com.lfq06.arknightsreader.turn

/** Screen-space rectangle of the book surface. */
data class TurnRect(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
)

/** Rendered page dimensions in canonical units. */
data class PageDims(
    val pageW: Double = 0.0,
    val pageH: Double = 0.0,
)

/** Single-page vs two-page spread layout. */
enum class PageMode { SINGLE, DOUBLE }
