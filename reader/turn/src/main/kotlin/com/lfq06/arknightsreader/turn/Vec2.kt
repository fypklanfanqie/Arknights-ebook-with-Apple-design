package com.lfq06.arknightsreader.turn

import kotlin.math.hypot

/** A finite-safe point in page coordinates. */
data class Vec2(val x: Double, val y: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite()

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Double): Vec2 = Vec2(x * scale, y * scale)

    fun length(): Double = hypot(x, y)
}
