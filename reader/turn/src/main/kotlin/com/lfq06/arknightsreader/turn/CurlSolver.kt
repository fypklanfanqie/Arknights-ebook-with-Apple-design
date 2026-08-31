package com.lfq06.arknightsreader.turn

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pure, finite-safe geometry for a page hinged on the x = 0 edge. */
object CurlSolver {
    private const val EPSILON = 1e-9
    private const val DEFAULT_EXTENT = 1.0

    fun constrainTarget(grab: Vec2, target: Vec2, pageWidth: Double, pageHeight: Double): Vec2 {
        val width = safeExtent(pageWidth)
        val height = safeExtent(pageHeight)
        val start = Vec2(grab.x.finiteOrZero().coerceIn(0.0, width), grab.y.finiteOrZero().coerceIn(0.0, height))
        val requested = Vec2(target.x.finiteOrZero(), target.y.finiteOrZero())
        val delta = requested - start
        if (delta.length() <= EPSILON) return start

        var maximumT = 1.0
        if (delta.x < -EPSILON) maximumT = min(maximumT, (0.0 - start.x) / delta.x)
        if (delta.x > EPSILON) maximumT = min(maximumT, (width - start.x) / delta.x)
        if (delta.y < -EPSILON) maximumT = min(maximumT, (0.0 - start.y) / delta.y)
        if (delta.y > EPSILON) maximumT = min(maximumT, (height - start.y) / delta.y)
        val t = maximumT.coerceIn(0.0, 1.0)
        return (start + delta * t).let { point ->
            Vec2(point.x.coerceIn(0.0, width), point.y.coerceIn(0.0, height))
        }
    }

    fun solve(
        grab: Vec2,
        target: Vec2,
        pageWidth: Double,
        pageHeight: Double,
        requestedRadius: Double,
    ): CurlState {
        val width = safeExtent(pageWidth)
        val height = safeExtent(pageHeight)
        val safeGrab = Vec2(
            grab.x.finiteOrZero().coerceIn(0.0, width),
            grab.y.finiteOrZero().coerceIn(0.0, height),
        )
        val constrained = constrainTarget(safeGrab, target, width, height)
        val radius = when {
            !requestedRadius.isFinite() -> width * 0.05
            requestedRadius <= 0.0 -> 0.0
            else -> requestedRadius.coerceAtMost(width * 4.0 + height * 4.0)
        }
        val translation = constrained - safeGrab
        val flat = translation.length() <= EPSILON
        val direction = normalized(translation)
        val normal = Vec2(-direction.y, direction.x)
        val band = if (radius > EPSILON) radius.coerceAtMost(width) else 0.0
        val start = (constrained.x - band).coerceIn(0.0, width)
        val end = (constrained.x + band).coerceIn(start, width)
        val depth = if (radius > EPSILON) radius * (1.0 - cos(min(Math.PI, translation.length() / radius))) else 0.0
        val angle = if (radius > EPSILON) {
            (translation.length() / radius).coerceIn(-Math.PI, Math.PI) * if (translation.x < 0.0) -1.0 else 1.0
        } else {
            Math.PI
        }
        val pivot = Vec2(start, safeGrab.y)
        return CurlState(
            grab = safeGrab,
            target = constrained,
            pageWidth = width,
            pageHeight = height,
            radius = radius,
            foldStart = start,
            foldEnd = end,
            translation = translation,
            bendNormal = normal,
            bendDepth = depth.finiteOrZero(),
            bendAngle = angle.finiteOrZero(),
            bendPivot = pivot,
            isFlat = flat,
        )
    }

    fun deformPoint(point: Vec2, state: CurlState): Vec2 {
        val safePoint = Vec2(point.x.finiteOrZero(), point.y.finiteOrZero())
        if (safePoint == state.grab) return state.target
        if (abs(safePoint.x) <= EPSILON) return Vec2(0.0, safePoint.y)
        if (state.isFlat) return safePoint

        val start = state.foldStart
        val end = state.foldEnd
        if (safePoint.x < start - EPSILON) return safePoint
        if (end - start <= EPSILON) return safePoint + state.translation
        if (safePoint.x > end + EPSILON) return safePoint + state.translation

        return rotateAround(safePoint, state.bendPivot, state.bendAngle)
    }

    private fun rotateAround(point: Vec2, pivot: Vec2, angle: Double): Vec2 {
        val cosine = cos(angle)
        val sine = sin(angle)
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Vec2(
            pivot.x + dx * cosine - dy * sine,
            pivot.y + dx * sine + dy * cosine,
        )
    }

    private fun normalized(vector: Vec2): Vec2 {
        val length = vector.length()
        return if (length.isFinite() && length > EPSILON) vector * (1.0 / length) else Vec2(0.0, 0.0)
    }

    private fun safeExtent(value: Double): Double =
        if (value.isFinite() && value > EPSILON) value else DEFAULT_EXTENT

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
}
