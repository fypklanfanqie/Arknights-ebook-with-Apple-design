package com.lfq06.arknightsreader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Seam-aligned page-curl mesh generation. Builds a regular cols x rows grid of
 * material-space triangles and clips every triangle exactly at the two
 * deformation seams (d = 0 and d = PI * r, where d is the signed distance to
 * the fold axis), so no emitted triangle spans two deformation branches. This
 * is what eliminates diagonal tearing and stretched slivers at sharp folds.
 *
 * Coordinate contract (canonical -> mesh):
 * - Canonical: hinge at x = 0, free edge at x = pageW, page TOP is a negative y
 *   (screen convention).
 * - Mesh: material coords p = (originX + u * pageW, (v - 0.5) * pageH); v = 1 is
 *   the page TOP with a POSITIVE y. Every crossing negates y, and a backward
 *   turn additionally mirrors x around the hinge. The GL vertex shader deforms
 *   these material coords with the same piecewise rule as [CurlSolver], so the
 *   axis and radius passed to [build] must already be in mesh coordinates.
 */
object CurlMesh {
    /** Radius below which the cylindrical band is treated as a sharp fold. */
    private const val FOLD_RADIUS_EPS = 1e-4

    private const val EPS = 1e-9

    /**
     * Worst-case vertex budget per base triangle when split across the three
     * deformation regions. Each half-plane clip can add at most one vertex per
     * polygon edge; three emits of a clipped triangle stay within 24 vertices,
     * which covers the two-clip cylinder band path with margin.
     */
    private const val PER_TRI_CAP = 24

    /** Reusable float storage for mesh positions and UVs. */
    class MeshOutput(val positions: FloatArray, val uvs: FloatArray)

    /** One built mesh: [vertexCount] vertices stored in the shared arrays. */
    class MeshResult(
        val vertexCount: Int,
        val positions: FloatArray,
        val uvs: FloatArray,
    )

    fun maxVertexCount(cols: Int, rows: Int): Int =
        maxOf(1, cols) * maxOf(1, rows) * 2 * PER_TRI_CAP

    fun allocOutput(cols: Int, rows: Int): MeshOutput {
        val max = maxVertexCount(cols, rows)
        return MeshOutput(FloatArray(max * 3), FloatArray(max * 2))
    }

    fun canonicalToMeshPoint(point: Vec2, dir: Int, originX: Double, pageW: Double): Vec2 {
        val x = point.x.finiteNumber(0.0)
        val y = point.y.finiteNumber(0.0)
        return if (dir > 0) {
            Vec2(originX + x, -y)
        } else {
            Vec2(originX + pageW - x, -y)
        }
    }

    fun canonicalToMeshVector(vector: Vec2, dir: Int): Vec2 {
        val x = vector.x.finiteNumber(0.0)
        val y = vector.y.finiteNumber(0.0)
        return if (dir > 0) Vec2(x, -y) else Vec2(-x, -y)
    }

    /**
     * Builds the seam-aligned mesh for one curl state. When [output] is
     * supplied, its arrays are reused in place; otherwise fresh arrays are
     * allocated. Non-finite inputs degrade safely: a degenerate normal falls
     * back to (1, 0) and non-finite geometry never emits non-finite vertices.
     *
     * Axis-point fallback: any non-finite component of [axisPoint] is replaced
     * per-component (x by `originX + pageW / 2`, y by 0), which is the page
     * center in mesh coordinates. A fully NaN axis therefore behaves exactly
     * like an axis through the page center -- distances become ordinary signed
     * distances from the center and the build stays seam-aligned.
     */
    fun build(
        pageW: Double,
        pageH: Double,
        originX: Double,
        cols: Int,
        rows: Int,
        axisPoint: Vec2,
        axisNormal: Vec2,
        radius: Double,
        output: MeshOutput? = null,
    ): MeshResult {
        val safeW = maxOf(0.0, pageW.finiteNumber(0.0))
        val safeH = maxOf(0.0, pageH.finiteNumber(0.0))
        val safeOriginX = originX.finiteNumber(0.0)
        val safeCols = max(1, cols)
        val safeRows = max(1, rows)
        val safeAxis = Vec2(
            axisPoint.x.finiteNumber(safeOriginX + safeW / 2.0),
            axisPoint.y.finiteNumber(0.0),
        )
        val safeRadius = maxOf(0.0, radius.finiteNumber(0.0))
        val n = normalize2(axisNormal)

        val out = output ?: allocOutput(cols, rows)
        val positions = out.positions
        val uvs = out.uvs
        val capacity = min(positions.size / 3, uvs.size / 2)
        var vertexCount = 0

        val cylHi = PI * safeRadius
        val hasCyl = safeRadius >= FOLD_RADIUS_EPS
        val minArea = 1e-9 * max(1.0, safeW) * max(1.0, safeH)

        // Grid vertices in material space; v = 1 is the page top (positive y).
        val gridCols = safeCols + 1
        val gridRows = safeRows + 1
        val grid = ArrayList<GridPoint>(gridCols * gridRows)
        for (iy in 0 until gridRows) {
            val v = iy.toDouble() / safeRows
            val py = -safeH / 2.0 + v * safeH
            for (ix in 0 until gridCols) {
                val u = ix.toDouble() / safeCols
                val px = safeOriginX + u * safeW
                grid.add(
                    GridPoint(
                        x = px,
                        y = py,
                        u = u,
                        v = v,
                        d = signedDistance(px, py, safeAxis, n),
                    ),
                )
            }
        }
        val at = { ix: Int, iy: Int -> grid[iy * gridCols + ix] }

        for (iy in 0 until safeRows) {
            for (ix in 0 until safeCols) {
                val a = at(ix, iy)
                val b = at(ix + 1, iy)
                val c = at(ix, iy + 1)
                val d = at(ix + 1, iy + 1)
                vertexCount += processTriangle(
                    listOf(a, b, c), positions, uvs, capacity, vertexCount,
                    safeAxis, n, safeRadius, cylHi, hasCyl, minArea,
                )
                vertexCount += processTriangle(
                    listOf(b, d, c), positions, uvs, capacity, vertexCount,
                    safeAxis, n, safeRadius, cylHi, hasCyl, minArea,
                )
            }
        }
        return MeshResult(vertexCount, positions, uvs)
    }

    private data class GridPoint(
        val x: Double,
        val y: Double,
        val u: Double,
        val v: Double,
        val d: Double,
    )

    /** Returns the number of vertices emitted. */
    private fun processTriangle(
        tri: List<GridPoint>,
        positions: FloatArray,
        uvs: FloatArray,
        capacity: Int,
        vertexCount: Int,
        axis: Vec2,
        n: Vec2,
        radius: Double,
        cylHi: Double,
        hasCyl: Boolean,
        minArea: Double,
    ): Int {
        var lo = 0
        var hi = 0
        for (p in tri) {
            if (p.d <= 0.0) lo++
            if (p.d >= cylHi) hi++
        }
        var emitted = 0
        if (!hasCyl) {
            // Sharp fold: the cylinder band is empty, so only d=0 is a seam.
            emitted += emitPoly(clipKeep(tri, 0.0), positions, uvs, capacity, vertexCount + emitted, axis, n, minArea)
            emitted += emitPoly(clipReject(tri, 0.0), positions, uvs, capacity, vertexCount + emitted, axis, n, minArea)
            return emitted
        }
        // Rounded fold: clip against d=0 (front|cylinder) and d=PI*r (cylinder|back).
        emitted += emitPoly(clipKeep(tri, 0.0), positions, uvs, capacity, vertexCount + emitted, axis, n, minArea)
        if (lo < 3 && hi < 3) {
            emitted += emitPoly(
                clipKeep(clipReject(tri, 0.0), cylHi),
                positions, uvs, capacity, vertexCount + emitted, axis, n, minArea,
            )
        }
        emitted += emitPoly(clipReject(tri, cylHi), positions, uvs, capacity, vertexCount + emitted, axis, n, minArea)
        return emitted
    }

    /** Sutherland-Hodgman clip against the half-plane d <= limit. */
    private fun clipKeep(poly: List<GridPoint>, limit: Double): List<GridPoint> {
        val result = ArrayList<GridPoint>(poly.size + 1)
        val m = poly.size
        for (i in 0 until m) {
            val a = poly[i]
            val b = poly[(i + 1) % m]
            val da = a.d - limit
            val db = b.d - limit
            val aIn = da <= EPS
            val bIn = db <= EPS
            if (aIn) result.add(a)
            if (aIn != bIn) result.add(lerp(a, b, da / (da - db)))
        }
        return result
    }

    /** Sutherland-Hodgman clip against the half-plane d >= limit. */
    private fun clipReject(poly: List<GridPoint>, limit: Double): List<GridPoint> {
        val result = ArrayList<GridPoint>(poly.size + 1)
        val m = poly.size
        for (i in 0 until m) {
            val a = poly[i]
            val b = poly[(i + 1) % m]
            val da = limit - a.d
            val db = limit - b.d
            val aIn = da <= EPS
            val bIn = db <= EPS
            if (aIn) result.add(a)
            if (aIn != bIn) result.add(lerp(a, b, da / (da - db)))
        }
        return result
    }

    private fun lerp(a: GridPoint, b: GridPoint, t: Double): GridPoint {
        val clampedT = t.finiteNumber(0.0).coerceIn(0.0, 1.0)
        return GridPoint(
            x = a.x + (b.x - a.x) * clampedT,
            y = a.y + (b.y - a.y) * clampedT,
            u = a.u + (b.u - a.u) * clampedT,
            v = a.v + (b.v - a.v) * clampedT,
            d = a.d + (b.d - a.d) * clampedT,
        )
    }

    /** Fan-triangulates a polygon; returns the number of vertices emitted. */
    private fun emitPoly(
        poly: List<GridPoint>,
        positions: FloatArray,
        uvs: FloatArray,
        capacity: Int,
        base: Int,
        axis: Vec2,
        n: Vec2,
        minArea: Double,
    ): Int {
        if (poly.size < 3) return 0
        var emitted = 0
        for (k in 2 until poly.size) {
            emitted += emitTriangle(poly[0], poly[k - 1], poly[k], positions, uvs, capacity, base + emitted, axis, n, minArea)
        }
        return emitted
    }

    private fun emitTriangle(
        a: GridPoint,
        b: GridPoint,
        c: GridPoint,
        positions: FloatArray,
        uvs: FloatArray,
        capacity: Int,
        base: Int,
        axis: Vec2,
        n: Vec2,
        minArea: Double,
    ): Int {
        if (base + 3 > capacity) return 0 // defensive; cap sized above worst case
        // Skip numerically-degenerate slivers that would render as zero-area noise.
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (abs(cross) < minArea) return 0
        if (!(a.x.isFinite() && a.y.isFinite() && b.x.isFinite() && b.y.isFinite() && c.x.isFinite() && c.y.isFinite())) return 0
        writeVertex(positions, uvs, base, a)
        writeVertex(positions, uvs, base + 1, b)
        writeVertex(positions, uvs, base + 2, c)
        return 3
    }

    private fun writeVertex(positions: FloatArray, uvs: FloatArray, index: Int, p: GridPoint) {
        positions[index * 3] = p.x.toFloat()
        positions[index * 3 + 1] = p.y.toFloat()
        positions[index * 3 + 2] = 0f
        uvs[index * 2] = p.u.toFloat()
        uvs[index * 2 + 1] = p.v.toFloat()
    }

    private fun signedDistance(px: Double, py: Double, axis: Vec2, n: Vec2): Double =
        (px - axis.x) * n.x + (py - axis.y) * n.y

    /**
     * Unit-normalizes [n] with hypot semantics: the length is computed without
     * squaring the components, so components up to Double.MAX_VALUE keep their
     * true direction instead of overflowing `x*x + y*y` to Infinity around
     * 2e154 and discarding the normal into the (1, 0) fallback. A zero or
     * non-finite input still falls back to (1, 0).
     */
    private fun normalize2(n: Vec2): Vec2 {
        val norm = kotlin.math.hypot(n.x, n.y)
        return if (norm.isFinite() && norm > EPS) Vec2(n.x / norm, n.y / norm) else Vec2(1.0, 0.0)
    }

    private fun Double.finiteNumber(fallback: Double): Double =
        if (isFinite()) this else fallback
}
