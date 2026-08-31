package com.lfq06.arknightsreader.turn

import kotlin.math.cos
import kotlin.math.sin

/** Direction of a swipe gesture on the page. */
enum class PageSide { RIGHT, LEFT }

/**
 * Pure, finite-safe developable page-curl mathematics in canonical page
 * coordinates: hinge on x = 0, free edge on x = W, y in [-H/2, +H/2].
 */
object CurlSolver {
    private const val EPS = 1e-9
    private const val FOLD_RADIUS_EPS = 1e-4

    fun toCanonical(point: Vec2, side: PageSide): Vec2 =
        if (side == PageSide.LEFT) Vec2(-point.x, point.y) else point

    fun fromCanonical(point: Vec2, side: PageSide): Vec2 =
        if (side == PageSide.LEFT) Vec2(-point.x, point.y) else point

    fun constrainTarget(grab: Vec2, target: Vec2, pageWidth: Double, pageHeight: Double): Vec2 {
        val w = clampNonNegative(finiteNumber(pageWidth, 0.0))
        val h = clampNonNegative(finiteNumber(pageHeight, 0.0))
        val g = sanitizePoint(grab, Vec2(w, 0.0))
        var q = sanitizePoint(target, g)
        if (!q.isFinite() || !g.isFinite()) return g

        val (hingeTop, hingeBottom) = hinges(h)
        val topRadius = (g - hingeTop).length()
        val bottomRadius = (g - hingeBottom).length()

        repeat(4) {
            q = projectDisk(q, hingeTop, topRadius)
            q = projectDisk(q, hingeBottom, bottomRadius)
        }

        val drag = g - q
        val dragLength = drag.length()
        if (dragLength > EPS && dragLength.isFinite()) {
            val normal = drag * (1.0 / dragLength)
            val allowance = -maxOf(
                (hingeTop - g) dot normal,
                (hingeBottom - g) dot normal,
            )
            val safeLength = maxOf(0.0, 2.0 * allowance)
            if (dragLength > safeLength) q = g - normal * safeLength
        }
        return if (q.isFinite()) q else g
    }

    fun solve(
        grab: Vec2,
        target: Vec2,
        pageWidth: Double,
        pageHeight: Double,
        requestedRadius: Double,
    ): CurlState {
        val w = clampNonNegative(finiteNumber(pageWidth, 0.0))
        val h = clampNonNegative(finiteNumber(pageHeight, 0.0))
        val g = sanitizePoint(grab, Vec2(w, 0.0))
        val rawTarget = sanitizePoint(target, g)
        val q = constrainTarget(g, rawTarget, w, h)
        val delta = g - q
        val l = delta.length()
        val n = normalize(delta, Vec2(1.0, 0.0))
        val t = Vec2(-n.y, n.x)
        val (hingeTop, hingeBottom) = hinges(h)
        var allowance = -maxOf((hingeTop - g) dot n, (hingeBottom - g) dot n)
        if (!allowance.isFinite()) allowance = 0.0

        val requested = maxOf(0.0, finiteNumber(requestedRadius, w * 0.05))
        var radius = if (l <= EPS) 0.0 else requested
        if (radius > 0.0) radius = minOf(radius, maxOf(0.0, (2.0 * allowance - l) / Math.PI))

        val dG = solveDistance(l, radius)
        val state = CurlState(
            grab = g,
            target = q,
            pageWidth = w,
            pageHeight = h,
            radius = radius,
            axisPoint = g - n * dG,
            axisNormal = n,
            axisTangent = t,
            grabDistance = l,
            progress = if (w > EPS) l / w else if (l > EPS) 1.0 else 0.0,
            phase = when {
                l <= EPS -> CurlState.Phase.FLAT
                radius < FOLD_RADIUS_EPS -> CurlState.Phase.FOLD
                else -> CurlState.Phase.CURL
            },
            finite = false,
        )
        val safe = state.copy(
            progress = state.progress.coerceIn(0.0, 1.0),
            finite = allFiniteState(state),
        )
        if (!safe.finite) {
            return CurlState(
                grab = g,
                target = g,
                pageWidth = w,
                pageHeight = h,
                radius = 0.0,
                axisPoint = g,
                axisNormal = Vec2(1.0, 0.0),
                axisTangent = Vec2(0.0, 1.0),
                grabDistance = 0.0,
                progress = 0.0,
                phase = CurlState.Phase.FLAT,
                finite = true,
            )
        }
        return safe
    }

    fun deformPoint(point: Vec2, state: CurlState): DeformedPoint {
        val p = sanitizePoint(point, Vec2(0.0, 0.0))
        val axisPoint = sanitizePoint(state.axisPoint, Vec2(0.0, 0.0))
        val n = normalize(sanitizePoint(state.axisNormal, Vec2(1.0, 0.0)), Vec2(1.0, 0.0))
        val t = normalize(Vec2(-n.y, n.x), Vec2(-n.y, n.x))
        val r = maxOf(0.0, finiteNumber(state.radius, 0.0))
        val grabDistance = finiteNumber(state.grabDistance, 0.0)
        if (grabDistance <= EPS) {
            return DeformedPoint(p.x, p.y, 0.0, 0.0, 0.0, 1.0, DeformedPoint.Region.FLAT_FRONT)
        }

        val rel = p - axisPoint
        val d = rel dot n
        val s = rel dot t
        val lat: Double
        val z: Double
        val nx: Double
        val ny: Double
        val nz: Double
        val region: DeformedPoint.Region
        if (d <= 0.0) {
            lat = d; z = 0.0; nx = 0.0; ny = 0.0; nz = 1.0
            region = DeformedPoint.Region.FLAT_FRONT
        } else if (r >= FOLD_RADIUS_EPS && d < Math.PI * r) {
            val angle = d / r
            lat = r * sin(angle)
            z = r * (1.0 - cos(angle))
            nx = -sin(angle) * n.x
            ny = -sin(angle) * n.y
            nz = cos(angle)
            region = DeformedPoint.Region.CYLINDRICAL_WRAP
        } else {
            lat = -(d - Math.PI * r)
            z = 2.0 * r
            nx = 0.0; ny = 0.0; nz = -1.0
            region = DeformedPoint.Region.FLAT_BACK
        }
        val out = axisPoint + t * s + n * lat
        return DeformedPoint(out.x, out.y, z, nx, ny, nz, region)
    }

    sealed interface ReleaseDecision {
        data object Commit : ReleaseDecision
        data object Cancel : ReleaseDecision
    }

    fun decideRelease(
        progress: Double,
        velocity: Any?,
        cancelled: Boolean,
    ): ReleaseDecision {
        if (cancelled) return ReleaseDecision.Cancel
        val clampedProgress = finiteNumber(progress, 0.0).coerceIn(0.0, 1.0)
        val velocityValue = when (velocity) {
            is VelocitySample -> if (velocity.ageMs.isFinite() && velocity.ageMs > 120.0) 0.0 else finiteNumber(velocity.value, 0.0)
            is Number -> finiteNumber(velocity.toDouble(), 0.0)
            else -> 0.0
        }
        if (velocityValue < -0.45) return ReleaseDecision.Cancel
        return if (clampedProgress >= 0.5 || velocityValue > 0.45) ReleaseDecision.Commit else ReleaseDecision.Cancel
    }

    private fun solveDistance(l: Double, r: Double): Double {
        if (l <= EPS) return 0.0
        if (r < FOLD_RADIUS_EPS) return l / 2.0
        if (l <= Math.PI * r) {
            var lo = 0.0
            var hi = Math.PI * r
            repeat(18) {
                val mid = (lo + hi) / 2.0
                val f = mid - r * sin(mid / r)
                if (f < l) lo = mid else hi = mid
            }
            return (lo + hi) / 2.0
        }
        return (l + Math.PI * r) / 2.0
    }

    private fun projectDisk(q: Vec2, center: Vec2, radius: Double): Vec2 {
        val delta = q - center
        val d = delta.length()
        if (!d.isFinite() || !radius.isFinite() || radius < 0.0) return center
        if (d <= radius || d <= EPS) return q
        return center + delta * (radius / d)
    }

    private fun hinges(pageHeight: Double): Pair<Vec2, Vec2> {
        val h = maxOf(0.0, finiteNumber(pageHeight, 0.0))
        return Vec2(0.0, -h / 2.0) to Vec2(0.0, h / 2.0)
    }

    private fun normalize(vector: Vec2, fallback: Vec2): Vec2 {
        val len = vector.length()
        return if (len > EPS && len.isFinite()) vector * (1.0 / len) else fallback
    }

    private fun sanitizePoint(point: Vec2, fallback: Vec2): Vec2 =
        Vec2(
            finiteNumber(point.x, fallback.x),
            finiteNumber(point.y, fallback.y),
        )

    private fun allFiniteState(state: CurlState): Boolean =
        state.axisPoint.isFinite() && state.axisNormal.isFinite() && state.axisTangent.isFinite() &&
            state.radius.isFinite() && state.grabDistance.isFinite() && state.progress.isFinite() &&
            state.target.isFinite() && state.grab.isFinite()

    private fun finiteNumber(value: Double, fallback: Double): Double =
        if (value.isFinite()) value else fallback

    private fun clampNonNegative(value: Double): Double = maxOf(0.0, value)

    private infix fun Vec2.dot(other: Vec2): Double = x * other.x + y * other.y
}
