package com.lfq06.arknightsreader.turn

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CurlSolverTest {
    private val width = 420.0
    private val height = 560.0
    private val eps = 1e-6

    @Test
    fun `canonical mapping round trips and mirrors left coordinates`() {
        val p = Vec2(318.0, -91.0)
        val right = PageSide.RIGHT
        val left = PageSide.LEFT

        val roundTrip = CurlSolver.fromCanonical(CurlSolver.toCanonical(p, right), right)
        assertEquals(p.x, roundTrip.x, 0.0)
        assertEquals(p.y, roundTrip.y, 0.0)

        val mirroredInput = CurlSolver.toCanonical(Vec2(-318.0, -91.0), left)
        assertEquals(p.x, mirroredInput.x, 0.0)
        assertEquals(p.y, mirroredInput.y, 0.0)

        val mirroredOutput = CurlSolver.fromCanonical(p, left)
        assertEquals(-318.0, mirroredOutput.x, 0.0)
        assertEquals(-91.0, mirroredOutput.y, 0.0)
    }

    @Test
    fun `canonical mapping sanitizes nonfinite coordinates`() {
        val nanX = CurlSolver.toCanonical(Vec2(Double.NaN, 5.0), PageSide.RIGHT)
        assertEquals(0.0, nanX.x, 0.0)
        assertEquals(5.0, nanX.y, 0.0)

        val nanMirrored = CurlSolver.toCanonical(Vec2(Double.NaN, 5.0), PageSide.LEFT)
        assertEquals(0.0, nanMirrored.x, 0.0)
        assertEquals(5.0, nanMirrored.y, 0.0)

        val inf = CurlSolver.toCanonical(Vec2(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), PageSide.RIGHT)
        assertEquals(0.0, inf.x, 0.0)
        assertEquals(0.0, inf.y, 0.0)

        val fromNan = CurlSolver.fromCanonical(Vec2(Double.NaN, 5.0), PageSide.LEFT)
        assertEquals(0.0, fromNan.x, 0.0)
        assertEquals(5.0, fromNan.y, 0.0)

        val fromInf = CurlSolver.fromCanonical(Vec2(Double.POSITIVE_INFINITY, -7.0), PageSide.LEFT)
        assertEquals(0.0, fromInf.x, 0.0)
        assertEquals(-7.0, fromInf.y, 0.0)
    }

    @Test
    fun `constrained target keeps a corner within both hinge reach disks`() {
        val grab = Vec2(420.0, -280.0)
        val constrained = CurlSolver.constrainTarget(grab, Vec2(-600.0, 500.0), 420.0, 560.0)

        assertTrue(constrained.isFinite())
        for (hinge in listOf(Vec2(0.0, -280.0), Vec2(0.0, 280.0))) {
            val reach = (grab - hinge).length()
            val actual = (constrained - hinge).length()
            assertTrue(actual <= reach + eps, "hinge $hinge: $actual exceeds reach $reach")
        }
    }

    @Test
    fun `canonical left and right inputs produce mirrored solves`() {
        val right = CurlSolver.solve(
            grab = Vec2(420.0, -180.0),
            target = Vec2(70.0, -90.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )
        val left = CurlSolver.solve(
            grab = CurlSolver.toCanonical(Vec2(420.0, -180.0), PageSide.LEFT),
            target = CurlSolver.toCanonical(Vec2(70.0, -90.0), PageSide.LEFT),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )

        assertEquals(-right.axisPoint.x, left.axisPoint.x, 0.0)
        assertEquals(right.axisPoint.y, left.axisPoint.y, 0.0)
        assertEquals(-right.axisNormal.x, left.axisNormal.x, 0.0)
        assertEquals(right.axisNormal.y, left.axisNormal.y, 0.0)
        assertEquals(right.axisTangent.x, left.axisTangent.x, 0.0)
        assertEquals(-right.axisTangent.y, left.axisTangent.y, 0.0)
        assertEquals(right.grabDistance, left.grabDistance, eps)
        assertEquals(right.progress, left.progress, eps)
    }

    @Test
    fun `huge drag with tiny width clamps progress instead of flat fallback`() {
        // Reference (node curl-math.js): progress=1, phase=fold — the L/W overflow
        // is clamped in place before the finiteness check, never discarded.
        val state = CurlSolver.solve(
            grab = Vec2(1e307, 0.0),
            target = Vec2(-1e307, 0.0),
            pageWidth = 5e-8,
            pageHeight = 560.0,
            requestedRadius = 20.0,
        )
        assertTrue(state.finite)
        assertEquals(1.0, state.progress, 1e-9)
        assertEquals(CurlState.Phase.FOLD, state.phase)
        assertTrue(state.grabDistance > 0.0)
    }

    @Test
    fun `huge finite drag keeps unclamped-overflow-free progress`() {
        // L/W = 4e307 is finite, so both reference and port agree: progress clamps to 1.
        val state = CurlSolver.solve(
            grab = Vec2(1e300, 0.0),
            target = Vec2(-1e300, 0.0),
            pageWidth = 5e-8,
            pageHeight = 560.0,
            requestedRadius = 20.0,
        )
        assertTrue(state.finite)
        assertEquals(1.0, state.progress, 1e-9)
        assertEquals(CurlState.Phase.FOLD, state.phase)
    }

    @Test
    fun `zero drag is finite and leaves the page flat`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, 0.0),
            target = Vec2(420.0, 0.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )
        val deformed = CurlSolver.deformPoint(Vec2(210.0, 0.0), state)

        assertTrue(state.finite)
        assertTrue(abs(state.progress) < eps)
        assertTrue(abs(deformed.z) < 1e-9)
        assertEquals(DeformedPoint.Region.FLAT_FRONT, deformed.region)
    }

    @Test
    fun `deformation preserves local material edge lengths`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, -180.0),
            target = Vec2(20.0, 40.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )
        for (p in listOf(Vec2(300.0, -100.0), Vec2(100.0, 100.0), Vec2(410.0, 260.0))) {
            val a = CurlSolver.deformPoint(p, state)
            val b = CurlSolver.deformPoint(Vec2(p.x + 1.0, p.y), state)
            val separation = distance3(a, b)
            assertTrue(abs(separation - 1.0) < 1e-4, "point $p edge length $separation")
        }
    }

    @Test
    fun `deform point uses the state tangent not a recomputed perpendicular`() {
        // Reference behavior: t = normalize(state.axisTangent, fallback (-n.y, n.x)).
        // Build a state whose stored tangent is deliberately NOT perpendicular to
        // the normal; deformPoint must follow the stored tangent.
        val axisPoint = Vec2(100.0, 50.0)
        val n = Vec2(1.0, 0.0)
        val oblique = CurlState(
            grab = Vec2(420.0, 50.0),
            target = axisPoint,
            pageWidth = width,
            pageHeight = height,
            radius = 20.0,
            axisPoint = axisPoint,
            axisNormal = n,
            axisTangent = Vec2(1.0, 1.0),
            grabDistance = 100.0,
            progress = 0.5,
            phase = CurlState.Phase.CURL,
            finite = true,
        )
        val probe = axisPoint + n * 10.0
        val deformed = CurlSolver.deformPoint(probe, oblique)

        // d = 10, angle = 0.5, lat = 20*sin(0.5); s = probe . t (stored, oblique).
        val lat = 20.0 * kotlin.math.sin(10.0 / 20.0)
        val invSqrt2 = 1.0 / kotlin.math.sqrt(2.0)
        val tNorm = Vec2(invSqrt2, invSqrt2)
        val s = (probe - axisPoint).let { it.x * tNorm.x + it.y * tNorm.y }
        // With the stored tangent: out = axis + t*s + n*lat.
        assertEquals(axisPoint.x + tNorm.x * s + lat, deformed.x, 1e-9)
        assertEquals(axisPoint.y + tNorm.y * s, deformed.y, 1e-9)

        // If deformPoint had recomputed the perpendicular of n=(1,0), t would be
        // (0,1): x would lose the t.x*s term and y would gain it. Assert the
        // perpendicular-recompute result is clearly different.
        val sPerp = (probe - axisPoint).let { it.x * 0.0 + it.y * 1.0 }
        val perpX = axisPoint.x + lat
        val perpY = axisPoint.y + sPerp
        assertTrue(
            abs(perpX - deformed.x) > 1.0 || abs(perpY - deformed.y) > 1.0,
            "deformPoint must not recompute the perpendicular tangent",
        )
    }

    @Test
    fun `deform point falls back to perpendicular when state tangent is degenerate`() {
        val axisPoint = Vec2(100.0, 50.0)
        val n = Vec2(1.0, 0.0)
        val lat = 20.0 * kotlin.math.sin(10.0 / 20.0)
        val probe = axisPoint + n * 10.0

        val degenerateTangents = listOf(Vec2(Double.NaN, Double.NaN), Vec2(0.0, 0.0))
        for (tangent in degenerateTangents) {
            val state = CurlState(
                grab = Vec2(420.0, 50.0),
                target = axisPoint,
                pageWidth = width,
                pageHeight = height,
                radius = 20.0,
                axisPoint = axisPoint,
                axisNormal = n,
                axisTangent = tangent,
                grabDistance = 100.0,
                progress = 0.5,
                phase = CurlState.Phase.CURL,
                finite = true,
            )
            val deformed = CurlSolver.deformPoint(probe, state)
            // Fallback t = (-n.y, n.x) = (0,1); s = rel . t = 0; out = axis + n*lat.
            assertEquals(axisPoint.x + lat, deformed.x, 1e-9, "tangent $tangent x")
            assertEquals(axisPoint.y, deformed.y, 1e-9, "tangent $tangent y")
            assertTrue(deformed.isFinite())
        }
    }

    @Test
    fun `cylindrical wrap preserves in band horizontal paper length`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, -180.0),
            target = Vec2(20.0, 40.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )
        val r = state.radius
        assertTrue(r > 1e-4)
        val axisD = 0.5 * r
        val inBand = state.axisPoint + state.axisNormal * axisD
        val first = CurlSolver.deformPoint(inBand, state)
        val second = CurlSolver.deformPoint(inBand + state.axisTangent * 1.5, state)
        assertEquals(1.5, distance3(first, second), 1e-4)
    }

    @Test
    fun `deformed material grab follows the constrained target in page plane`() {
        val grab = Vec2(420.0, -180.0)
        val target = Vec2(-80.0, 40.0)
        val q = CurlSolver.constrainTarget(grab, target, width, height)
        val state = CurlSolver.solve(grab, target, width, height, requestedRadius = 20.0)
        val g = CurlSolver.deformPoint(grab, state)

        assertTrue(hypot(g.x - q.x, g.y - q.y) < 1e-4)
    }

    @Test
    fun `fixed hinge endpoints stay on the front flat sheet`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, -180.0),
            target = Vec2(90.0, 40.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 20.0,
        )
        for (hinge in listOf(Vec2(0.0, -height / 2.0), Vec2(0.0, height / 2.0))) {
            val out = CurlSolver.deformPoint(hinge, state)
            assertTrue(distance3(out, DeformedPoint(hinge.x, hinge.y, 0.0, 0.0, 0.0, 1.0, out.region)) < 1e-5)
            assertEquals(DeformedPoint.Region.FLAT_FRONT, out.region)
        }
    }

    @Test
    fun `near hinge constrained drags preserve hinge and grab without stretching`() {
        val grab = Vec2(0.0, -245.0)
        val target = Vec2(-420.0, -560.0)
        val q = CurlSolver.constrainTarget(grab, target, width, height)
        val state = CurlSolver.solve(grab, target, width, height, requestedRadius = 20.0)

        for (hinge in listOf(Vec2(0.0, -height / 2.0), Vec2(0.0, height / 2.0))) {
            val out = CurlSolver.deformPoint(hinge, state)
            val offset = kotlin.math.sqrt(
                (out.x - hinge.x) * (out.x - hinge.x) +
                    (out.y - hinge.y) * (out.y - hinge.y) +
                    out.z * out.z,
            )
            assertTrue(offset < 1e-5)
        }
        val g = CurlSolver.deformPoint(grab, state)
        val grabOffset = kotlin.math.sqrt(
            (g.x - q.x) * (g.x - q.x) +
                (g.y - q.y) * (g.y - q.y) +
                g.z * g.z,
        )
        assertTrue(grabOffset < 1e-5)
    }

    @Test
    fun `release decisions honor cancellation threshold and fresh velocity`() {
        assertEquals(
            CurlSolver.ReleaseDecision.Cancel,
            CurlSolver.decideRelease(progress = 0.9, velocity = 1.0, cancelled = true),
        )
        assertEquals(
            CurlSolver.ReleaseDecision.Commit,
            CurlSolver.decideRelease(progress = 0.5, velocity = 0.0, cancelled = false),
        )
        assertEquals(
            CurlSolver.ReleaseDecision.Commit,
            CurlSolver.decideRelease(progress = 0.2, velocity = 0.8, cancelled = false),
        )
        assertEquals(
            CurlSolver.ReleaseDecision.Cancel,
            CurlSolver.decideRelease(progress = 0.4, velocity = -0.8, cancelled = false),
        )
        assertEquals(
            CurlSolver.ReleaseDecision.Cancel,
            CurlSolver.decideRelease(
                progress = 0.2,
                velocity = VelocitySample(value = 0.8, ageMs = 121.0),
                cancelled = false,
            ),
        )
    }

    @Test
    fun `release decisions treat NaN progress and NaN velocity as neutral`() {
        // NaN progress falls back to 0 -> below commit threshold.
        assertEquals(
            CurlSolver.ReleaseDecision.Cancel,
            CurlSolver.decideRelease(progress = Double.NaN, velocity = 0.0, cancelled = false),
        )
        // NaN velocity falls back to 0 -> no velocity boost, progress decides.
        assertEquals(
            CurlSolver.ReleaseDecision.Cancel,
            CurlSolver.decideRelease(progress = 0.2, velocity = Double.NaN, cancelled = false),
        )
        assertEquals(
            CurlSolver.ReleaseDecision.Commit,
            CurlSolver.decideRelease(progress = 0.6, velocity = Double.NaN, cancelled = false),
        )
        // VelocitySample with NaN ageMs is not provably stale -> value counts as fresh.
        assertEquals(
            CurlSolver.ReleaseDecision.Commit,
            CurlSolver.decideRelease(
                progress = 0.2,
                velocity = VelocitySample(value = 0.8, ageMs = Double.NaN),
                cancelled = false,
            ),
        )
    }

    @Test
    fun `solver and deformation remain finite over deterministic edge grid`() {
        val grabs = listOf(
            Vec2(width, -height / 2.0),
            Vec2(width, 0.0),
            Vec2(width, height / 2.0),
            Vec2(0.0, 0.0),
        )
        val targets = buildList {
            for (ix in -2..4) for (iy in -2..4) add(Vec2(ix * width / 2.0, iy * height / 4.0))
        }
        var count = 0
        for (grab in grabs) for (target in targets) {
            val state = CurlSolver.solve(grab, target, width, height, requestedRadius = 20.0)
            assertTrue(state.finite, "state not finite for grab=$grab target=$target")
            for (ix in 0..10) for (iy in 0..10) {
                val p = Vec2(ix * width / 10.0, -height / 2.0 + iy * height / 10.0)
                val deformed = CurlSolver.deformPoint(p, state)
                assertTrue(deformed.isFinite(), "deformed not finite at $p for grab=$grab target=$target")
                count++
            }
        }
        assertTrue(count >= 200, "expected at least 200 samples, got $count")
    }

    @Test
    fun `nonfinite radius uses width fallback while zero radius stays a sharp fold`() {
        val grab = Vec2(320.0, 0.0)
        val target = Vec2(100.0, 40.0)

        val fallback = CurlSolver.solve(grab, target, width, height, requestedRadius = Double.NaN)
        val sharp = CurlSolver.solve(grab, target, width, height, requestedRadius = 0.0)

        assertEquals(width * 0.05, fallback.radius, eps)
        assertEquals(CurlState.Phase.CURL, fallback.phase)
        assertEquals(0.0, sharp.radius, eps)
        assertEquals(CurlState.Phase.FOLD, sharp.phase)
        assertTrue(fallback.finite)
        assertTrue(sharp.finite)
    }

    @Test
    fun `extreme inputs never create nonfinite outputs`() {
        val values = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e300, 1e300)

        values.forEach { value ->
            val state = CurlSolver.solve(
                grab = Vec2(value, 100.0),
                target = Vec2(200.0, value),
                pageWidth = value,
                pageHeight = height,
                requestedRadius = value,
            )
            assertTrue(state.isFinite(), "state should be finite for $value")
            val deformed = CurlSolver.deformPoint(Vec2(value, value), state)
            assertTrue(deformed.isFinite(), "deformed should be finite for $value")
        }
    }

    private fun distance3(first: DeformedPoint, second: DeformedPoint): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val dz = second.z - first.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
