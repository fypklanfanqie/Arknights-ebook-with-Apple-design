package com.lfq06.arknightsreader.turn

import kotlin.math.cos
import kotlin.math.sin

/** Direction of a swipe gesture on the page. */
enum class PageSide { RIGHT, LEFT }

/**
 * Pure, finite-safe developable page-curl mathematics in canonical page
 * coordinates: hinge on x = 0, free edge on x = W, y in [-H/2, +H/2].
 *
 * All public entry points accept arbitrary (possibly non-finite) input and
 * sanitize it before use: non-finite coordinates are replaced with a safe
 * fallback, so results are always finite.
 */
object CurlSolver {
    private const val EPS = 1e-9
    private const val FOLD_RADIUS_EPS = 1e-4
    private const val COMMIT_PROGRESS = 0.5
    private const val VELOCITY_THRESHOLD = 0.45
    private const val VELOCITY_FRESH_MS = 120.0

    /** Mirrors [point] into canonical coordinates for [side]; non-finite coordinates become 0. */
    fun toCanonical(point: Vec2, side: PageSide): Vec2 {
        val p = sanitizePoint(point, Vec2(0.0, 0.0))
        return if (side == PageSide.LEFT) Vec2(-p.x, p.y) else p
    }

    /** Maps canonical [point] back to [side] screen coordinates; non-finite coordinates become 0. */
    fun fromCanonical(point: Vec2, side: PageSide): Vec2 {
        val p = sanitizePoint(point, Vec2(0.0, 0.0))
        return if (side == PageSide.LEFT) Vec2(-p.x, p.y) else p
    }

    /**
     * Clamps [target] so the resulting drag stays feasible: the target lies
     * within both hinge reach disks and the drag never exceeds twice the
     * hinge allowance, so both hinge endpoints stay fixed without stretching.
     *
     * Both points are interpreted in canonical coordinates (hinge x = 0,
     * free edge x = [pageWidth], y in [-[pageHeight]/2, +[pageHeight]/2]).
     */
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

    /**
     * Solves the curl state for one drag from [grab] toward [target].
     *
     * All inputs are interpreted in canonical coordinates (hinge x = 0, free
     * edge x = [pageWidth], y in [-[pageHeight]/2, +[pageHeight]/2]); the
     * target is constrained via [constrainTarget] first. Progress is clamped
     * to [0, 1] in place, matching the reference implementation: a drag longer
     * than the page clamps to 1 instead of falling back to the flat state.
     */
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
        // Match the reference: clamp progress in place (coerceIn first) before the
        // finiteness check, so an overflowing L/W ratio clamps to 1 rather than
        // discarding the whole drag into the flat fallback.
        val rawProgress = if (w > EPS) l / w else if (l > EPS) 1.0 else 0.0
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
            progress = rawProgress.coerceIn(0.0, 1.0),
            phase = when {
                l <= EPS -> CurlState.Phase.FLAT
                radius < FOLD_RADIUS_EPS -> CurlState.Phase.FOLD
                else -> CurlState.Phase.CURL
            },
            finite = false,
        )
        val safe = state.copy(finite = allFiniteState(state))
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

    /**
     * Maps one canonical page point through the curl deformation described by
     * [state].
     *
     * The tangent is taken from [CurlState.axisTangent] (normalized), matching
     * the reference behavior; only a degenerate stored tangent (non-finite or
     * zero length) falls back to the perpendicular of the normal, (-n.y, n.x).
     */
    fun deformPoint(point: Vec2, state: CurlState): DeformedPoint {
        val p = sanitizePoint(point, Vec2(0.0, 0.0))
        val axisPoint = sanitizePoint(state.axisPoint, Vec2(0.0, 0.0))
        val n = normalize(sanitizePoint(state.axisNormal, Vec2(1.0, 0.0)), Vec2(1.0, 0.0))
        val t = normalize(sanitizePoint(state.axisTangent, Vec2(0.0, 0.0)), Vec2(-n.y, n.x))
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

    /**
     * Decides whether releasing a drag should commit the page turn.
     *
     * [progress] is clamped to [0, 1] (non-finite treated as 0); a plain
     * [velocity] value is used as-is (non-finite treated as 0). Cancellation
     * always wins, a strongly negative velocity cancels, and otherwise the
     * drag commits when [progress] >= [COMMIT_PROGRESS] or the release
     * velocity exceeds [VELOCITY_THRESHOLD].
     */
    fun decideRelease(
        progress: Double,
        velocity: Double,
        cancelled: Boolean,
    ): ReleaseDecision {
        if (cancelled) return ReleaseDecision.Cancel
        val clampedProgress = finiteNumber(progress, 0.0).coerceIn(0.0, 1.0)
        val velocityValue = finiteNumber(velocity, 0.0)
        if (velocityValue < -VELOCITY_THRESHOLD) return ReleaseDecision.Cancel
        return if (clampedProgress >= COMMIT_PROGRESS || velocityValue > VELOCITY_THRESHOLD) {
            ReleaseDecision.Commit
        } else {
            ReleaseDecision.Cancel
        }
    }

    /**
     * Decides whether releasing a drag should commit the page turn, using a
     * timestamped velocity sample. A sample older than [VELOCITY_FRESH_MS] is
     * treated as stale (velocity 0); a non-finite [VelocitySample.ageMs] is
     * considered fresh, and a non-finite [VelocitySample.value] counts as 0.
     */
    fun decideRelease(
        progress: Double,
        velocity: VelocitySample,
        cancelled: Boolean,
    ): ReleaseDecision = decideRelease(
        progress = progress,
        velocity = if (velocity.ageMs.isFinite() && velocity.ageMs > VELOCITY_FRESH_MS) 0.0 else velocity.value,
        cancelled = cancelled,
    )

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
