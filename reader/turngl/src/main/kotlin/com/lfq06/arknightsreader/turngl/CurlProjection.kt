package com.lfq06.arknightsreader.turngl

import kotlin.math.max

/**
 * Pure-Kotlin projection math for the curl draw path (C-4), so the frustum
 * contract is unit-testable on the JVM (android.opengl.Matrix is a stub in
 * unit tests).
 *
 * Camera model: perspective eye on the page-center axis at [CurlEglFrame.EYE_Z]
 * page pixels, looking down -z with up = +y. The frustum is scaled so the
 * z = 0 plane (the flat page) maps 1:1 onto the viewport: the flat page is
 * pixel-exact while the curled part gains perspective foreshortening. Near and
 * far planes cover the full curl height (2 * r) plus a margin, so the curl
 * top is never clipped by the depth range.
 */
object CurlProjection {

    data class Frustum(
        val near: Float,
        val far: Float,
        /** Half-width at the z = 0 plane (== vpW / 2 by construction). */
        val halfWidthAtPage: Float,
        /** Half-height at the z = 0 plane (== vpH / 2 by construction). */
        val halfHeightAtPage: Float,
        /** Depth range the frustum reserves for the curl above z = 0. */
        val curlDepth: Float,
    )

    /**
     * Computes the frustum for one frame: [CurlEglFrame.EYE_Z] eye distance,
     * depth coverage for a curl of [radius] page pixels on a page of
     * [pageW] x [pageH], viewport [vpW] x [vpH].
     */
    fun frustum(
        pageW: Double,
        pageH: Double,
        radius: Double,
        vpW: Int,
        vpH: Int,
    ): Frustum {
        val w = max(pageW.toFloat(), 1f)
        val eyeZ = CurlEglFrame.EYE_Z
        // Depth reserved for the curl: 2 * r at minimum, never less than
        // MIN_CURL_FRACTION of the page width (radius ramp-up safety), plus a
        // flat margin. near/far are symmetric around the page plane.
        val curlDepth = max(
            CurlEglFrame.MIN_CURL_FRACTION * w,
            radius.toFloat() * 2f,
        ) + CurlEglFrame.CURL_Z_MARGIN
        return Frustum(
            near = eyeZ - curlDepth,
            far = eyeZ + curlDepth,
            halfWidthAtPage = vpW / 2f,
            halfHeightAtPage = vpH / 2f,
            curlDepth = curlDepth,
        )
    }

    /**
     * Builds the full MVP (column-major 4x4) mapping material space
     * (x in [0, pageW], y in [-pageH/2, +pageH/2], z up to 2r) to clip space
     * for a viewport of [vpW] x [vpH]. The page plane is centered on the
     * viewport; +y (mesh bottom) maps to clip -y (GL bottom).
     */
    fun mvp(
        params: CurlFrameParams,
        vpW: Int,
        vpH: Int,
        out: FloatArray,
    ) {
        val f = frustum(params.pageW, params.pageH, params.radius, vpW, vpH)
        val eyeZ = CurlEglFrame.EYE_Z
        val w = max(params.pageW.toFloat(), 1f)

        // Perspective frustum at the near plane, scaled so the z = 0 plane
        // covers exactly the viewport.
        val scale = f.near / eyeZ
        val left = -f.halfWidthAtPage * scale
        val right = f.halfWidthAtPage * scale
        val bottom = -f.halfHeightAtPage * scale
        val top = f.halfHeightAtPage * scale
        val near = f.near
        val far = f.far

        // Column-major frustum.
        val p = FloatArray(16)
        p[0] = 2f * near / (right - left)
        p[5] = 2f * near / (top - bottom)
        p[8] = (right + left) / (right - left)
        p[9] = (top + bottom) / (top - bottom)
        p[10] = -(far + near) / (far - near)
        p[11] = -1f
        p[14] = -2f * far * near / (far - near)

        // View: translate the eye (pageW/2, 0, eyeZ) to the origin.
        val v = FloatArray(16)
        java.util.Arrays.fill(v, 0f)
        v[0] = 1f; v[5] = 1f; v[10] = 1f; v[15] = 1f
        v[12] = -w / 2f
        v[13] = 0f
        v[14] = -eyeZ

        // Mesh y points down; flip y in clip space.
        val flip = FloatArray(16)
        java.util.Arrays.fill(flip, 0f)
        flip[0] = 1f; flip[5] = -1f; flip[10] = 1f; flip[15] = 1f

        // out = flip * P * V.
        val pv = FloatArray(16)
        mul(pv, p, v)
        mul(out, flip, pv)
    }

    /** Column-major 4x4 multiply: out = a * b. out must not alias a or b. */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
    }

    /** Applies a column-major 4x4 to (x, y, z, 1); returns clip coords. */
    fun apply(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        return floatArrayOf(cx, cy, cz, cw)
    }
}
