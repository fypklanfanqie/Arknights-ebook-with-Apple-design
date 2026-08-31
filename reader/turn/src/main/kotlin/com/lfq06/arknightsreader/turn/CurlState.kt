package com.lfq06.arknightsreader.turn

/**
 * Canonical page coordinates: the hinge sits on x = 0, the free edge on
 * x = W, and the vertical extent spans y in [-H/2, +H/2] (top is negative).
 */
data class CurlState(
    val grab: Vec2,
    val target: Vec2,
    val pageWidth: Double,
    val pageHeight: Double,
    val radius: Double,
    /** Point on the crease axis, expressed as G - n * dG. */
    val axisPoint: Vec2,
    /** Drag direction (from target toward grab), unit length or (1, 0) fallback. */
    val axisNormal: Vec2,
    /** Perpendicular of axisNormal: (-n.y, n.x). */
    val axisTangent: Vec2,
    val grabDistance: Double,
    val progress: Double,
    val phase: Phase,
    val finite: Boolean,
) {
    enum class Phase { FLAT, FOLD, CURL }

    val hingeTop: Vec2 get() = Vec2(0.0, -pageHeight / 2.0)
    val hingeBottom: Vec2 get() = Vec2(0.0, pageHeight / 2.0)

    fun isFinite(): Boolean = finite &&
        grab.isFinite() && target.isFinite() &&
        pageWidth.isFinite() && pageHeight.isFinite() && radius.isFinite() &&
        axisPoint.isFinite() && axisNormal.isFinite() && axisTangent.isFinite() &&
        grabDistance.isFinite() && progress.isFinite()
}
