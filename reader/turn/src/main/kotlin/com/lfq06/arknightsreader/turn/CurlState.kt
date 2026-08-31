package com.lfq06.arknightsreader.turn

/** Immutable parameters for one page-curl deformation. */
data class CurlState(
    val grab: Vec2,
    val target: Vec2,
    val pageWidth: Double,
    val pageHeight: Double,
    val radius: Double,
    val foldStart: Double,
    val foldEnd: Double,
    val translation: Vec2,
    val bendNormal: Vec2,
    val bendDepth: Double,
    val bendAngle: Double,
    val bendPivot: Vec2,
    val isFlat: Boolean,
) {
    val hingeTop: Vec2 get() = Vec2(0.0, 0.0)
    val hingeBottom: Vec2 get() = Vec2(0.0, pageHeight)

    fun isFinite(): Boolean =
        grab.isFinite() && target.isFinite() &&
            pageWidth.isFinite() && pageHeight.isFinite() && radius.isFinite() &&
            foldStart.isFinite() && foldEnd.isFinite() && translation.isFinite() &&
            bendNormal.isFinite() && bendDepth.isFinite() && bendAngle.isFinite() && bendPivot.isFinite()
}
