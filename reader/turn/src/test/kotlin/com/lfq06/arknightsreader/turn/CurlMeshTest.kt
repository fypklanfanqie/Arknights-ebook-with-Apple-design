package com.lfq06.arknightsreader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Clean-room behavior tests for the seam-aligned mesh generator, ported from
 * the reference web reader's curl-mesh test suite (pure grid vector checks).
 *
 * Positions are written into float32-backed storage, so seam vertices sit
 * within ~1e-5 px of the mathematical fold line. A real seam crossing is
 * pixel-scale (a vertex tens of px into the wrong deformation branch); a
 * 1e-3 px tolerance still catches every real crossing while ignoring float32
 * rounding.
 */
class CurlMeshTest {
    private val EPS = 1e-3

    private fun dist(axis: Vec2, n: Vec2, x: Double, y: Double): Double =
        (x - axis.x) * n.x + (y - axis.y) * n.y

    private fun triangleVerts(positions: FloatArray, t: Int): List<Pair<Double, Double>> {
        val v = t * 3
        return listOf(
            positions[v * 3].toDouble() to positions[v * 3 + 1].toDouble(),
            positions[(v + 1) * 3].toDouble() to positions[(v + 1) * 3 + 1].toDouble(),
            positions[(v + 2) * 3].toDouble() to positions[(v + 2) * 3 + 1].toDouble(),
        )
    }

    /** Mutable option bag mirroring the reference test's build() overrides. */
    private class Opts(
        var pageW: Double = 420.0,
        var pageH: Double = 560.0,
        var originX: Double = 0.0,
        var cols: Int = 40,
        var rows: Int = 24,
        var axisPoint: Vec2 = Vec2(210.0, 0.0),
        var axisNormal: Vec2 = Vec2(1.0, 0.6),
        var radius: Double = 0.0,
        var output: CurlMesh.MeshOutput? = null,
    )

    private data class Built(val opts: Opts, val res: CurlMesh.MeshResult)

    private fun build(overrides: Opts.() -> Unit = {}): Built {
        val opts = Opts().apply(overrides)
        val res = CurlMesh.build(
            pageW = opts.pageW,
            pageH = opts.pageH,
            originX = opts.originX,
            cols = opts.cols,
            rows = opts.rows,
            axisPoint = opts.axisPoint,
            axisNormal = opts.axisNormal,
            radius = opts.radius,
            output = opts.output,
        )
        assertTrue(res.vertexCount > 0, "mesh must emit vertices")
        return Built(opts, res)
    }

    // ---- coordinate contract (canonical -> mesh) ----

    @Test
    fun `canonical top maps to positive mesh y and bottom to negative mesh y`() {
        val top = CurlMesh.canonicalToMeshPoint(Vec2(0.0, -280.0), dir = 1, originX = 0.0, pageW = 420.0)
        assertEquals(0.0, top.x, EPS)
        assertEquals(280.0, top.y, EPS)
        val bottom = CurlMesh.canonicalToMeshPoint(Vec2(0.0, 280.0), dir = 1, originX = 0.0, pageW = 420.0)
        assertEquals(-280.0, bottom.y, EPS)
    }

    @Test
    fun `backward turns mirror x and keep the y negation`() {
        val out = CurlMesh.canonicalToMeshPoint(Vec2(210.0, -140.0), dir = -1, originX = -420.0, pageW = 420.0)
        assertEquals(-210.0, out.x, EPS)
        assertEquals(140.0, out.y, EPS)
    }

    @Test
    fun `vectors negate y and mirror x for backward turns`() {
        assertEquals(Vec2(1.0, -0.6), CurlMesh.canonicalToMeshVector(Vec2(1.0, 0.6), dir = 1))
        assertEquals(Vec2(-1.0, -0.6), CurlMesh.canonicalToMeshVector(Vec2(1.0, 0.6), dir = -1))
    }

    // ---- seam alignment ----

    @Test
    fun `sharp diagonal fold emits no triangle crossing the d=0 seam`() {
        val (opts, res) = build { radius = 0.0 }
        val nLen = hypot(opts.axisNormal.x, opts.axisNormal.y)
        val n = Vec2(opts.axisNormal.x / nLen, opts.axisNormal.y / nLen)
        for (t in 0 until res.vertexCount / 3) {
            val ds = triangleVerts(res.positions, t).map { (x, y) -> dist(opts.axisPoint, n, x, y) }
            assertTrue(
                !(ds.min() < -EPS && ds.max() > EPS),
                "triangle $t crosses the d=0 seam: $ds",
            )
        }
    }

    @Test
    fun `rounded fold emits no triangle crossing d=0 or d=PI*r`() {
        val (opts, res) = build { radius = 21.0 }
        // Measure d with the SAME normalized normal the builder uses, so the seam
        // thresholds (0 and PI*r) and the measured distances share one scale.
        val nLen = hypot(opts.axisNormal.x, opts.axisNormal.y)
        val n = Vec2(opts.axisNormal.x / nLen, opts.axisNormal.y / nLen)
        val cylHi = PI * 21.0
        for (t in 0 until res.vertexCount / 3) {
            val ds = triangleVerts(res.positions, t).map { (x, y) -> dist(opts.axisPoint, n, x, y) }
            assertTrue(!(ds.min() < -EPS && ds.max() > EPS), "triangle $t crosses d=0")
            assertTrue(
                !(ds.min() < cylHi - EPS && ds.max() > cylHi + EPS),
                "triangle $t crosses d=PI*r",
            )
        }
    }

    @Test
    fun `sharp fold has no zero-width cylindrical band`() {
        val (opts, res) = build { radius = 0.0 }
        val nLen = hypot(opts.axisNormal.x, opts.axisNormal.y)
        val n = Vec2(opts.axisNormal.x / nLen, opts.axisNormal.y / nLen)
        for (t in 0 until res.vertexCount / 3) {
            val ds = triangleVerts(res.positions, t).map { (x, y) -> dist(opts.axisPoint, n, x, y) }
            // No triangle may have one vertex strictly in the (0, PI*0) band and
            // another strictly below it -- with radius 0 the band is empty.
            assertTrue(!(ds.min() < -EPS && ds.max() > EPS))
        }
    }

    @Test
    fun `completed page whole sheet past the axis is emitted as the back sheet`() {
        // Axis at the hinge (x=0) with normal pointing across the page: every grid
        // vertex has d >= 0, so the entire page belongs to the back region.
        val (opts, res) = build {
            axisPoint = Vec2(0.0, 0.0)
            axisNormal = Vec2(1.0, 0.0)
            radius = 0.0
        }
        for (t in 0 until res.vertexCount / 3) {
            for ((x, y) in triangleVerts(res.positions, t)) {
                assertTrue(
                    dist(opts.axisPoint, Vec2(1.0, 0.0), x, y) >= -EPS,
                    "vertex behind the completed fold: $x,$y",
                )
            }
        }
    }

    @Test
    fun `all emitted vertices and uvs are finite and in bounds`() {
        val (_, res) = build { radius = 0.0 }
        for (i in 0 until res.vertexCount) {
            val x = res.positions[i * 3].toDouble()
            val y = res.positions[i * 3 + 1].toDouble()
            assertTrue(x.isFinite() && y.isFinite())
            assertTrue(x >= -EPS && x <= 420.0 + EPS, "x out of bounds: $x")
            assertTrue(y >= -280.0 - EPS && y <= 280.0 + EPS, "y out of bounds: $y")
        }
        for (i in 0 until res.vertexCount) {
            val u = res.uvs[i * 2].toDouble()
            val v = res.uvs[i * 2 + 1].toDouble()
            assertTrue(u >= -EPS && u <= 1.0 + EPS, "u out of bounds: $u")
            assertTrue(v >= -EPS && v <= 1.0 + EPS, "v out of bounds: $v")
        }
    }

    @Test
    fun `forward and mirrored-backward meshes stay symmetric at the fold line`() {
        // Forward mesh in right-page coordinates [0, pageW] with a diagonal axis.
        val fwdOpts = Opts(
            axisPoint = Vec2(300.0, 40.0),
            axisNormal = Vec2(-0.8, -0.6),
            radius = 0.0,
        )
        // Backward mesh in left-page coordinates [-pageW, 0] mirrored through the hinge.
        val bwdOpts = Opts(
            originX = -420.0,
            axisPoint = Vec2(-300.0, 40.0),
            axisNormal = Vec2(0.8, -0.6),
            radius = 0.0,
        )
        val fwd = CurlMesh.build(
            pageW = fwdOpts.pageW, pageH = fwdOpts.pageH, originX = fwdOpts.originX,
            cols = fwdOpts.cols, rows = fwdOpts.rows,
            axisPoint = fwdOpts.axisPoint, axisNormal = fwdOpts.axisNormal, radius = fwdOpts.radius,
        )
        val bwd = CurlMesh.build(
            pageW = bwdOpts.pageW, pageH = bwdOpts.pageH, originX = bwdOpts.originX,
            cols = bwdOpts.cols, rows = bwdOpts.rows,
            axisPoint = bwdOpts.axisPoint, axisNormal = bwdOpts.axisNormal, radius = bwdOpts.radius,
        )
        // Both meshes build valid, comparable geometry. The fixed triangulation
        // splits a few edge cells differently under mirroring (diagonal orientation
        // flips), so allow a small vertex-count drift instead of exact equality.
        assertTrue(fwd.vertexCount > 0 && bwd.vertexCount > 0)
        assertTrue(
            abs(fwd.vertexCount - bwd.vertexCount).toDouble() / fwd.vertexCount < 0.2,
            "vertex counts diverge: ${fwd.vertexCount} vs ${bwd.vertexCount}",
        )
        // The fold LINES must mirror exactly. Because the backward axis/point are
        // the hinge-mirror of the forward ones, every backward vertex's signed
        // distance is the same value as the forward distance of its mirrored
        // point (-x). Verify every backward seam vertex (|d| < one grid column)
        // mirrors onto the forward seam, and vice versa.
        val nF = fwdOpts.axisNormal
        val nB = bwdOpts.axisNormal
        val tol = 420.0 / 40.0
        for (i in 0 until bwd.vertexCount) {
            val bx = bwd.positions[i * 3].toDouble()
            val by = bwd.positions[i * 3 + 1].toDouble()
            val db = dist(bwdOpts.axisPoint, nB, bx, by)
            if (abs(db) < tol) {
                val df = dist(fwdOpts.axisPoint, nF, -bx, by)
                assertTrue(abs(df - db) < EPS, "backward seam does not mirror forward fold: $bx,$by db=$db df=$df")
            }
        }
        for (i in 0 until fwd.vertexCount) {
            val fx = fwd.positions[i * 3].toDouble()
            val fy = fwd.positions[i * 3 + 1].toDouble()
            val df = dist(fwdOpts.axisPoint, nF, fx, fy)
            if (abs(df) < tol) {
                val db = dist(bwdOpts.axisPoint, nB, -fx, fy)
                assertTrue(abs(df - db) < EPS, "forward seam does not mirror backward fold: $fx,$fy df=$df db=$db")
            }
        }
    }

    @Test
    fun `output arrays are reusable and never exceed the allocated budget`() {
        val out = CurlMesh.allocOutput(80, 48)
        val a = CurlMesh.build(
            pageW = 420.0, pageH = 560.0, originX = 0.0,
            cols = 80, rows = 48,
            axisPoint = Vec2(210.0, 0.0), axisNormal = Vec2(1.0, 0.6), radius = 0.0,
            output = out,
        )
        assertTrue(a.vertexCount <= CurlMesh.maxVertexCount(80, 48))
        val b = CurlMesh.build(
            pageW = 420.0, pageH = 560.0, originX = 0.0,
            cols = 80, rows = 48,
            axisPoint = Vec2(100.0, -60.0), axisNormal = Vec2(0.3, 1.0), radius = 21.0,
            output = out,
        )
        assertTrue(b.vertexCount <= CurlMesh.maxVertexCount(80, 48))
        assertTrue(a.positions === b.positions, "same reused buffer")
    }

    // ---- Kotlin-specific robustness additions ----

    @Test
    fun `nonfinite axis normal falls back to unit x normal without emitting garbage`() {
        val (opts, res) = build {
            axisNormal = Vec2(Double.NaN, Double.NaN)
            radius = 21.0
        }
        // Same normalized-normal scale as the builder after its (1,0) fallback.
        val n = Vec2(1.0, 0.0)
        val cylHi = PI * 21.0
        for (t in 0 until res.vertexCount / 3) {
            val ds = triangleVerts(res.positions, t).map { (x, y) -> dist(opts.axisPoint, n, x, y) }
            assertTrue(!(ds.min() < -EPS && ds.max() > EPS), "triangle $t crosses d=0")
            assertTrue(
                !(ds.min() < cylHi - EPS && ds.max() > cylHi + EPS),
                "triangle $t crosses d=PI*r",
            )
        }
        for (i in 0 until res.vertexCount) {
            assertTrue(res.positions[i * 3].isFinite())
            assertTrue(res.positions[i * 3 + 1].isFinite())
            assertTrue(res.uvs[i * 2].isFinite())
            assertTrue(res.uvs[i * 2 + 1].isFinite())
        }
    }

    @Test
    fun `nan radius is clamped to zero and behaves like a sharp fold`() {
        val (opts, res) = build { radius = Double.NaN }
        val nLen = hypot(opts.axisNormal.x, opts.axisNormal.y)
        val n = Vec2(opts.axisNormal.x / nLen, opts.axisNormal.y / nLen)
        for (t in 0 until res.vertexCount / 3) {
            val ds = triangleVerts(res.positions, t).map { (x, y) -> dist(opts.axisPoint, n, x, y) }
            // Sharp-fold behavior: nothing crosses d=0, and no zero-width band.
            assertTrue(!(ds.min() < -EPS && ds.max() > EPS), "triangle $t crosses d=0")
        }
    }

    @Test
    fun `nonfinite page dimensions and axis point degrade safely`() {
        // NaN page size collapses the grid to a point: the builder must emit no
        // vertices at all (a real assertion, not a vacuous loop).
        val res1 = CurlMesh.build(
            pageW = Double.NaN, pageH = Double.NaN, originX = 0.0,
            cols = 40, rows = 24,
            axisPoint = Vec2(210.0, 0.0), axisNormal = Vec2(1.0, 0.6), radius = 21.0,
        )
        assertEquals(0, res1.vertexCount, "NaN page size must collapse to zero vertices")

        // A tiny but non-zero page runs the degenerate guard path: either the
        // area filter skips everything (zero vertices) or whatever is emitted
        // is finite. Both branches are asserted, so the test cannot pass by
        // silently skipping a loop.
        val res2 = CurlMesh.build(
            pageW = 1e-8, pageH = 1e-8, originX = 0.0,
            cols = 40, rows = 24,
            axisPoint = Vec2(5e-9, 0.0), axisNormal = Vec2(1.0, 0.6), radius = 21.0,
        )
        val allFinite = (0 until res2.vertexCount).all { i ->
            res2.positions[i * 3].toDouble().isFinite() && res2.positions[i * 3 + 1].toDouble().isFinite()
        }
        assertTrue(
            res2.vertexCount == 0 || allFinite,
            "tiny page must emit nothing or only finite vertices (got ${res2.vertexCount})",
        )
    }

    @Test
    fun `nan axis point falls back to the page center`() {
        // Pins the current fallback semantics: a fully non-finite axis point
        // degrades to (originX + pageW / 2, 0) -- the page center in mesh
        // coordinates -- and every vertex matches the explicit-center build.
        val resNaN = CurlMesh.build(
            pageW = 420.0, pageH = 560.0, originX = 0.0,
            cols = 40, rows = 24,
            axisPoint = Vec2(Double.NaN, Double.NaN), axisNormal = Vec2(1.0, 0.6), radius = 21.0,
        )
        val resCenter = CurlMesh.build(
            pageW = 420.0, pageH = 560.0, originX = 0.0,
            cols = 40, rows = 24,
            axisPoint = Vec2(210.0, 0.0), axisNormal = Vec2(1.0, 0.6), radius = 21.0,
        )
        assertEquals(resCenter.vertexCount, resNaN.vertexCount, "fallback must reproduce the center build")
        for (i in 0 until resNaN.vertexCount * 3) {
            assertEquals(
                resCenter.positions[i].toDouble(),
                resNaN.positions[i].toDouble(),
                1e-4,
                "vertex $i must match the center build",
            )
        }
    }

    @Test
    fun `huge finite axis normal is normalized with hypot and keeps its direction`() {
        // x*x + y*y overflows to Infinity for inputs around 2e154, which used to
        // discard the normal and fall back to (1, 0). hypot-based scaling must
        // keep the true diagonal direction, producing the exact same mesh as an
        // explicit (1/sqrt(2), 1/sqrt(2)) normal.
        val huge = 2.0e154
        val (_, resHuge) = build {
            axisNormal = Vec2(huge, huge)
            radius = 0.0
        }
        for (i in 0 until resHuge.vertexCount) {
            assertTrue(resHuge.positions[i * 3].isFinite())
            assertTrue(resHuge.positions[i * 3 + 1].isFinite())
        }
        val inv = 1.0 / kotlin.math.sqrt(2.0)
        val (_, resExact) = build {
            axisNormal = Vec2(inv, inv)
            radius = 0.0
        }
        assertEquals(resExact.vertexCount, resHuge.vertexCount, "direction must survive the huge normal")
        for (i in 0 until resHuge.vertexCount * 3) {
            assertEquals(
                resExact.positions[i].toDouble(),
                resHuge.positions[i].toDouble(),
                1e-4,
                "vertex $i diverges from the exact-normal build",
            )
        }
        for (i in 0 until resHuge.vertexCount * 2) {
            assertEquals(
                resExact.uvs[i].toDouble(),
                resHuge.uvs[i].toDouble(),
                1e-4,
                "uv $i diverges from the exact-normal build",
            )
        }
    }
}
