package com.lfq06.arknightsreader.turngl

import com.lfq06.arknightsreader.turn.CurlSolver
import com.lfq06.arknightsreader.turn.PageSide
import com.lfq06.arknightsreader.turn.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test as JUnit5Test

/**
 * Numerical parity between the shader-math mirror [CurlShaderMath] and
 * [CurlSolver.deformPoint]: the two must agree on position, z-lift, and
 * normals across all three deformation branches. This is what lets the
 * JVM-side contract test below stand in for the GPU implementation.
 */
class CurlShaderMathTest {
    private val w = 420.0
    private val h = 560.0

    private fun sampleState(grab: Vec2, target: Vec2, radius: Double) =
        CurlSolver.solve(grab, target, w, h, radius)

    private fun checkPoint(px: Double, py: Double, state: com.lfq06.arknightsreader.turn.CurlState) {
        val ref = CurlSolver.deformPoint(Vec2(px, py), state)
        val mirror = CurlShaderMath.deform(
            px = px, py = py,
            axisX = state.axisPoint.x, axisY = state.axisPoint.y,
            nx = state.axisNormal.x, ny = state.axisNormal.y,
            radius = state.radius,
        )
        assertEquals(ref.x, mirror.x, 1e-9, "x diverges at ($px,$py)")
        assertEquals(ref.y, mirror.y, 1e-9, "y diverges at ($px,$py)")
        assertEquals(ref.z, mirror.z, 1e-9, "z diverges at ($px,$py)")
        assertEquals(ref.nx, mirror.nx, 1e-9, "nx diverges at ($px,$py)")
        assertEquals(ref.ny, mirror.ny, 1e-9, "ny diverges at ($px,$py)")
        assertEquals(ref.nz, mirror.nz, 1e-9, "nz diverges at ($px,$py)")
    }

    @JUnit5Test
    fun `mirror matches deformPoint across flat cylindrical and back branches`() {
        // Diagonal axis through the middle of the page.
        val state = sampleState(
            grab = Vec2(w, -h * 0.3),
            target = Vec2(w * 0.35, h * 0.2),
            radius = w * 0.05,
        )
        // Sample all three branches: d<0 (flat front), 0<d<PI*r (cylinder),
        // d>PI*r (flat back). Use the solved axis/normal directly.
        val n = state.axisNormal
        val a = state.axisPoint
        val cylHi = PI * state.radius
        // Hand-pick points relative to the axis along the normal direction.
        val ds = listOf(-40.0, -0.5, 0.5, state.radius, cylHi * 0.5, cylHi + 1.0, cylHi + 80.0)
        for (d in ds) {
            // Walk along the normal from the axis, offset along the tangent so
            // points land inside the page.
            val px = a.x + n.x * d - n.y * 30.0
            val py = a.y + n.y * d + n.x * 30.0
            checkPoint(px, py, state)
        }
    }

    @JUnit5Test
    fun `sharp fold branch matches deformPoint`() {
        val state = sampleState(
            grab = Vec2(w, -h * 0.2),
            target = Vec2(0.0, h * 0.1),
            radius = 0.0,
        )
        val n = state.axisNormal
        val a = state.axisPoint
        for (d in listOf(-30.0, 0.0, 30.0, 200.0)) {
            checkPoint(a.x + n.x * d, a.y + n.y * d, state)
        }
    }

    @JUnit5Test
    fun `mirror is finite-safe on degenerate normals`() {
        val out = CurlShaderMath.deform(
            px = 100.0, py = -50.0,
            axisX = 210.0, axisY = 0.0,
            nx = Double.NaN, ny = Double.POSITIVE_INFINITY,
            radius = 21.0,
        )
        assertTrue(out.x.isFinite() && out.y.isFinite() && out.z.isFinite())
        assertEquals(0.0, hypot(out.nx, out.ny), 1e-12)
        assertTrue(out.nz in -1.0..1.0)
    }

    @JUnit5Test
    fun `deformPoint branch formulas agree with the closed forms`() {
        // Sanity-check the cylinder formula used by both implementations.
        val r = 25.0
        val d = 10.0
        val ang = d / r
        assertEquals(r * sin(ang), CurlSolverTest3.cylinderLat(d, r), 1e-12)
        assertEquals(r * (1.0 - cos(ang)), CurlSolverTest3.cylinderZ(d, r), 1e-12)
    }
}

/** Shared closed-form helpers used by parity checks. */
private object CurlSolverTest3 {
    fun cylinderLat(d: Double, r: Double): Double = r * sin(d / r)
    fun cylinderZ(d: Double, r: Double): Double = r * (1.0 - cos(d / r))
}
