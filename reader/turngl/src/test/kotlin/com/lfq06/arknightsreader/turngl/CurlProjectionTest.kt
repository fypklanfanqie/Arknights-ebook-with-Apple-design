package com.lfq06.arknightsreader.turngl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-4 contract: the projection must keep the flat page pixel-exact on the
 * viewport and must never clip the curled part (curl top reaches z = 2*r).
 * Pure Kotlin math, so this runs on the JVM without GL.
 */
class CurlProjectionTest {

    private fun params(radius: Double) = CurlFrameParams(
        axisPoint = com.lfq06.arknightsreader.turn.Vec2(0.0, 0.0),
        axisNormal = com.lfq06.arknightsreader.turn.Vec2(1.0, 0.0),
        radius = radius,
        pageW = 420.0,
        pageH = 560.0,
        pageVisible = true,
    )

    @Test
    fun `near and far cover the full curl height`() {
        // radiusFraction 0.05 * 420 = 21 -> curl top z = 42. The old camera
        // (near=2, far=4 around eye z=3) clipped everything past z=1: RED
        // against the pre-fix code.
        val f = CurlProjection.frustum(420.0, 560.0, radius = 21.0, vpW = 1080, vpH = 1920)
        assertTrue(f.far - CurlEglFrame.EYE_Z >= 42f + CurlEglFrame.CURL_Z_MARGIN - 0.01f,
            "far plane must clear 2*r=42 plus margin (far-eyeZ=${f.far - CurlEglFrame.EYE_Z})")
        assertTrue(f.near < CurlEglFrame.EYE_Z, "near must sit below the eye")
        assertTrue(f.far > CurlEglFrame.EYE_Z, "far must sit above the eye")
        assertTrue(f.near > 0f, "near must stay positive for a valid frustum")
    }

    @Test
    fun `zero radius still reserves curl depth for the ramp`() {
        val f = CurlProjection.frustum(420.0, 560.0, radius = 0.0, vpW = 1080, vpH = 1920)
        assertTrue(f.far - CurlEglFrame.EYE_Z >= CurlEglFrame.MIN_CURL_FRACTION * 420f,
            "even a flat frame must reserve depth for radius ramp-up")
    }

    @Test
    fun `flat page maps pixel-exact onto the viewport`() {
        val out = FloatArray(16)
        CurlProjection.mvp(params(radius = 0.0), vpW = 1080, vpH = 1920, out = out)
        // Page (420 wide) centered in a 1080-wide viewport: 1 page pixel =
        // 1 viewport pixel, so the page left edge sits at viewport pixel 330
        // -> NDC x = 2*330/1080 - 1 = -0.3889 (NOT -1: the page is smaller
        // than the viewport). Mesh +y is page bottom -> clip +y after flip.
        val tl = CurlProjection.apply(out, 0f, -280f, 0f)
        val br = CurlProjection.apply(out, 420f, 280f, 0f)
        val expectedX = 2f * 330f / 1080f - 1f
        // Page top at viewport pixel 680; after the y-flip, GL ndc y = 1 at
        // the viewport top, so ndc = 1 - 2*680/1920 = +0.2917.
        val expectedTopY = 1f - 2f * 680f / 1920f
        assertEquals(expectedX, tl[0] / tl[3], 1e-3f, "page left edge -> pixel-exact NDC x")
        assertEquals(expectedTopY, tl[1] / tl[3], 1e-3f, "mesh top (y=-280) -> page top, y-flipped")
        val expectedBottomY = -expectedTopY
        assertEquals(-expectedX, br[0] / br[3], 1e-3f, "page right edge mirrors")
        assertEquals(expectedBottomY, br[1] / br[3], 1e-3f, "mesh bottom mirrors")
        // 1:1 pixel scale: full NDC span of the page = pageW / halfVp.
        assertEquals(420f / 540f, br[0] / br[3] - tl[0] / tl[3], 1e-3f, "page width maps 1:1")
        assertEquals(560f / 960f, kotlin.math.abs(br[1] / br[3] - tl[1] / tl[3]), 1e-3f, "page height maps 1:1")
    }

    @Test
    fun `curl top stays inside the clip volume`() {
        // The curled sheet top: z = 2*r = 42 at the axis point. It must be
        // strictly inside the depth range (not clipped), RED against the old
        // near=2/far=4 camera.
        val out = FloatArray(16)
        CurlProjection.mvp(params(radius = 21.0), vpW = 1080, vpH = 1920, out = out)
        val top = CurlProjection.apply(out, 0f, 0f, 42f)
        val ndcZ = top[2] / top[3]
        assertTrue(ndcZ > -1f && ndcZ < 1f, "curl top z=42 must be inside the depth range (ndcZ=$ndcZ)")
        // Flat page corners stay pixel-exact in the same frame.
        val tl = CurlProjection.apply(out, 0f, -280f, 0f)
        val expectedX = 2f * 330f / 1080f - 1f
        assertEquals(expectedX, tl[0] / tl[3], 1e-3f, "page geometry unchanged by the curl")
    }

    @Test
    fun `column major multiply matches manual math`() {
        val a = FloatArray(16); val b = FloatArray(16); val out = FloatArray(16)
        for (i in a.indices) { a[i] = i + 1f; b[i] = (i + 1f) * 0.5f }
        CurlProjection.mul(out, a, b)
        // out[col*4+row] = sum_k a[k*4+row] * b[col*4+k]
        assertEquals(a[0] * b[0] + a[4] * b[1] + a[8] * b[2] + a[12] * b[3], out[0], 1e-5f)
        assertEquals(a[1] * b[4] + a[5] * b[5] + a[9] * b[6] + a[13] * b[7], out[5], 1e-5f)
    }

    /**
     * C-4 viewport source contract: the draw path must size the viewport from
     * the real surface dimensions passed by the host, not the canonical page
     * size. CurlEglFrame.draw has no JVM proxy seam (direct GLES20), so this
     * locks the wiring at source level; the numeric correctness of the MVP is
     * covered by the projection tests above.
     */
    @Test
    fun `draw path viewports the surface dimensions`() {
        val src = java.io.File("src/main/kotlin/com/lfq06/arknightsreader/turngl/CurlEglFrame.kt")
            .readText()
        assertTrue(Regex("glViewport\\(0, 0, vpW, vpH\\)").containsMatchIn(src),
            "draw must call glViewport with the surface-sized vpW/vpH")
        assertTrue(Regex("val vpW = surfaceW\\.coerceAtLeast\\(1\\)").containsMatchIn(src),
            "viewport width must come from surfaceW")
        // The old bug: viewport derived from params.pageW/pageH.
        assertTrue(!Regex("glViewport\\(0, 0, params\\.pageW").containsMatchIn(src),
            "viewport must NOT use the canonical page size")
    }
}
