package com.lfq06.arknightsreader.turn

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CurlSolverTest {
    private val width = 400.0
    private val height = 600.0
    private val epsilon = 1e-7

    @Test
    fun `zero drag produces a finite flat state`() {
        val grab = Vec2(320.0, 240.0)

        val state = CurlSolver.solve(grab, grab, width, height, requestedRadius = 24.0)

        assertTrue(state.isFlat)
        assertTrue(state.isFinite())
        assertPointEquals(grab, state.target)
        assertPointEquals(Vec2(123.0, 456.0), CurlSolver.deformPoint(Vec2(123.0, 456.0), state))
    }

    @Test
    fun `constrained target projects grab along reachable ray`() {
        val grab = Vec2(350.0, 300.0)
        val requested = Vec2(-500.0, 900.0)

        val constrained = CurlSolver.constrainTarget(grab, requested, width, height)

        assertTrue(constrained.x in 0.0..width)
        assertTrue(constrained.y in 0.0..height)
        assertTrue(hypot(constrained.x - grab.x, constrained.y - grab.y) < hypot(requested.x - grab.x, requested.y - grab.y))
        assertTrue(abs(cross(grab, requested, constrained)) < epsilon)
    }

    @Test
    fun `both hinge endpoints remain unmoved`() {
        val state = CurlSolver.solve(
            grab = Vec2(340.0, 290.0),
            target = Vec2(80.0, 420.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 28.0,
        )

        assertPointEquals(Vec2(0.0, 0.0), CurlSolver.deformPoint(Vec2(0.0, 0.0), state))
        assertPointEquals(Vec2(0.0, height), CurlSolver.deformPoint(Vec2(0.0, height), state))
    }

    @Test
    fun `nearby points preserve local paper distance`() {
        val state = CurlSolver.solve(
            grab = Vec2(360.0, 250.0),
            target = Vec2(110.0, 400.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 30.0,
        )
        val first = Vec2(280.0, 250.0)
        val second = Vec2(281.5, 252.0)

        val deformedFirst = CurlSolver.deformPoint(first, state)
        val deformedSecond = CurlSolver.deformPoint(second, state)

        assertEquals(distance(first, second), distance(deformedFirst, deformedSecond), 1e-6)
    }

    @Test
    fun `nonfinite radius uses width fallback while nonpositive radius stays sharp`() {
        val grab = Vec2(320.0, 240.0)
        val target = Vec2(100.0, 400.0)

        val fallback = CurlSolver.solve(grab, target, width, height, requestedRadius = Double.NaN)
        val sharp = CurlSolver.solve(grab, target, width, height, requestedRadius = 0.0)

        assertEquals(width * 0.05, fallback.radius, epsilon)
        assertEquals(0.0, sharp.radius, epsilon)
        assertTrue(fallback.isFinite())
        assertTrue(sharp.isFinite())
        assertTrue(!sharp.isFlat)
    }

    @Test
    fun `cylindrical segment preserves horizontal paper length`() {
        val state = CurlSolver.solve(
            grab = Vec2(360.0, 250.0),
            target = Vec2(100.0, 400.0),
            pageWidth = width,
            pageHeight = height,
            requestedRadius = 30.0,
        )
        val first = Vec2((state.foldStart + state.foldEnd) * 0.5, 250.0)
        val second = first.copy(x = first.x + 1.5)

        val deformedFirst = CurlSolver.deformPoint(first, state)
        val deformedSecond = CurlSolver.deformPoint(second, state)

        assertEquals(distance(first, second), distance(deformedFirst, deformedSecond), 1e-6)
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
            assertFalse(deformed.x.isNaN() || deformed.x.isInfinite())
            assertFalse(deformed.y.isNaN() || deformed.y.isInfinite())
        }
    }

    private fun cross(origin: Vec2, rayPoint: Vec2, point: Vec2): Double =
        (rayPoint.x - origin.x) * (point.y - origin.y) - (rayPoint.y - origin.y) * (point.x - origin.x)

    private fun distance(first: Vec2, second: Vec2): Double = hypot(second.x - first.x, second.y - first.y)

    private fun assertPointEquals(expected: Vec2, actual: Vec2) {
        assertEquals(expected.x, actual.x, epsilon)
        assertEquals(expected.y, actual.y, epsilon)
    }
}
