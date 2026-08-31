package com.lfq06.arknightsreader.turn

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cross-checks the Kotlin port against selected numeric vectors produced by
 * the reference behavior specification (same inputs, same expected outputs).
 */
class CurlSolverParityTest {
    @Test
    fun `curl solve matches reference vectors`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, -180.0),
            target = Vec2(20.0, 40.0),
            pageWidth = 420.0,
            pageHeight = 560.0,
            requestedRadius = 20.0,
        )
        assertEquals(192.47286538342942, state.axisPoint.x, 1e-9)
        assertEquals(-54.86007596088619, state.axisPoint.y, 1e-9)
        assertEquals(0.8762159086766471, state.axisNormal.x, 1e-9)
        assertEquals(-0.4819187497721559, state.axisNormal.y, 1e-9)
        assertEquals(0.4819187497721559, state.axisTangent.x, 1e-9)
        assertEquals(0.8762159086766471, state.axisTangent.y, 1e-9)
        assertEquals(20.0, state.radius, 1e-9)
        assertEquals(456.50848842053307, state.grabDistance, 1e-9)
        assertEquals(CurlState.Phase.CURL, state.phase)

        val wrap = CurlSolver.deformPoint(Vec2(315.0, 140.0), state)
        assertEquals(314.13088987424993, wrap.x, 1e-9)
        assertEquals(140.4780105691626, wrap.y, 1e-9)
        assertEquals(4.356845734606969, wrap.z, 1e-9)
        assertEquals(-0.5459530479575316, wrap.nx, 1e-9)
        assertEquals(0.30027417637664233, wrap.ny, 1e-9)
        assertEquals(0.7821577132696516, wrap.nz, 1e-9)
        assertEquals(DeformedPoint.Region.CYLINDRICAL_WRAP, wrap.region)

        val front = CurlSolver.deformPoint(Vec2(105.0, -140.0), state)
        assertEquals(DeformedPoint.Region.FLAT_FRONT, front.region)
        assertEquals(0.0, front.z, 1e-12)
    }

    @Test
    fun `fold solve matches reference vectors`() {
        val state = CurlSolver.solve(
            grab = Vec2(420.0, -280.0),
            target = Vec2(-420.0, -280.0),
            pageWidth = 420.0,
            pageHeight = 560.0,
            requestedRadius = 20.0,
        )
        assertEquals(Vec2(0.0, -280.0), state.axisPoint)
        assertEquals(Vec2(1.0, 0.0), state.axisNormal)
        assertEquals(0.0, state.radius, 1e-9)
        assertEquals(840.0, state.grabDistance, 1e-9)
        assertEquals(1.0, state.progress, 1e-9)
        assertEquals(CurlState.Phase.FOLD, state.phase)

        val back = CurlSolver.deformPoint(Vec2(105.0, -140.0), state)
        assertEquals(-105.0, back.x, 1e-9)
        assertEquals(-140.0, back.y, 1e-9)
        assertEquals(0.0, back.z, 1e-9)
        assertEquals(-1.0, back.nz, 1e-9)
        assertEquals(DeformedPoint.Region.FLAT_BACK, back.region)
    }

    @Test
    fun `allowance caps radius so both hinges stay fixed`() {
        // Tiny drag near a hinge: allowance shrinks and radius must cap below requested.
        val grab = Vec2(30.0, -245.0)
        val target = Vec2(28.0, -245.0)
        val state = CurlSolver.solve(
            grab = grab,
            target = target,
            pageWidth = 420.0,
            pageHeight = 560.0,
            requestedRadius = 20.0,
        )
        // grab=(30,-245) sits 30 above the bottom hinge (0,-280); the drag direction
        // is +x, so allowance = 30 and the radius cap is (2*30 - 2) / pi = 58/pi.
        val dragLength = (grab - target).length()
        assertEquals(58.0 / Math.PI, state.radius, 1e-9)
        assertEquals(dragLength, state.grabDistance, 1e-9)
        assertTrue(state.radius < 20.0, "radius ${state.radius} should be capped by allowance")
        assertTrue(state.radius.isFinite())
        for (hinge in listOf(Vec2(0.0, -280.0), Vec2(0.0, 280.0))) {
            val out = CurlSolver.deformPoint(hinge, state)
            val offset = kotlin.math.sqrt(
                (out.x - hinge.x) * (out.x - hinge.x) +
                    (out.y - hinge.y) * (out.y - hinge.y) +
                    out.z * out.z,
            )
            assertTrue(offset < 1e-5, "hinge $hinge moved by $offset")
        }
    }

    @Test
    fun `overshoot targets shorten without stretching near hinge`() {
        // Verified against the reference (node curl-math.js): the raw drag of
        // 630 exceeds 2*allowance = 51.4996, so the constrained target shortens
        // to exactly the safe length while both hinge endpoints stay fixed.
        val grab = Vec2(30.0, 250.0)
        val target = Vec2(-600.0, 250.0)
        val state = CurlSolver.solve(
            grab = grab,
            target = target,
            pageWidth = 420.0,
            pageHeight = 560.0,
            requestedRadius = 12.0,
        )
        val q = CurlSolver.constrainTarget(grab, target, 420.0, 560.0)
        val rawDrag = (grab - target).length()

        assertTrue(state.grabDistance > 0.0, "constrained drag must be non-degenerate, got ${state.grabDistance}")
        assertTrue(rawDrag > 2.0 * (state.grabDistance / 2.0) + 1.0, "input must actually overshoot")
        assertTrue(abs(state.grabDistance - rawDrag) > 1.0, "target must have been shortened by allowance, not passed through")
        val g = CurlSolver.deformPoint(grab, state)
        val offset = kotlin.math.sqrt((g.x - q.x) * (g.x - q.x) + (g.y - q.y) * (g.y - q.y))
        assertTrue(offset < 1e-5, "grab ${g.x},${g.y} does not follow constrained target $q (offset $offset)")
        assertTrue(abs(state.grabDistance - (grab - q).length()) < 1e-9)
        for (hinge in listOf(Vec2(0.0, -280.0), Vec2(0.0, 280.0))) {
            val out = CurlSolver.deformPoint(hinge, state)
            val hingeOffset = kotlin.math.sqrt(
                (out.x - hinge.x) * (out.x - hinge.x) +
                    (out.y - hinge.y) * (out.y - hinge.y) +
                    out.z * out.z,
            )
            assertTrue(hingeOffset < 1e-5, "hinge $hinge moved by $hingeOffset")
        }
    }
}
