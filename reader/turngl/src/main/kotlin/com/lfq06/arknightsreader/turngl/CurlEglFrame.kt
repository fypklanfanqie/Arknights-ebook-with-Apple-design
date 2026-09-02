package com.lfq06.arknightsreader.turngl

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.FloatBuffer

/**
 * Stateful draw helpers bound to one EGL context. Split from
 * [CurlTextureViewHost] so the host only owns threading/EGL and every GLES
 * call in the draw path lives here.
 *
 * Draw model per frame (matches the reference engine's material split):
 * 1. Front pass: cull back faces, uIsBack = 0, offset = +halfThickness.
 * 2. Back pass: cull front faces, uIsBack = 1, offset = -halfThickness.
 * Both passes share the same VBO geometry (owned by [CurlMeshBuffers]); the
 * sub-pixel normal offset keeps the two sheets from fighting over the same
 * depth.
 *
 * Projection (C-4): the page lies in the z = 0 plane; the curl lifts vertices
 * up to z = 2*r (r up to radiusFraction * pageW). A perspective camera sits on
 * the eye axis through the page center at [EYE_Z]; the frustum is scaled so
 * the z = 0 plane maps 1:1 onto the viewport, keeping the flat page pixel-
 * exact while the curled part gains a slight perspective foreshortening. Near
 * and far planes cover [zMin, 2r + margin], so the curl top is never clipped.
 */
object CurlEglFrame {

    private var program = 0
    private var uniformsReady = false
    private var uMvp = 0
    private var uAxisPoint = 0
    private var uAxisNormal = 0
    private var uRadius = 0
    private var uOffset = 0
    private var uFront = 0
    private var uBack = 0
    private var uLight = 0
    private var uIsBack = 0
    private var uCreaseGain = 0

    private var frontTex = 0
    private var backTex = 0

    private val mvp = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)

    private const val LIGHT_X = 0.25f
    private const val LIGHT_Y = -0.35f
    private const val LIGHT_Z = 0.9f

    /** Eye distance of the perspective camera, in page pixels. */
    const val EYE_Z = 1200f

    /**
     * Safety margin above the maximum curl height (2 * r) added to the far
     * plane, in page pixels.
     */
    const val CURL_Z_MARGIN = 8f

    /**
     * Minimum curl height the near/far planes must cover even when the current
     * radius is 0, so a radius ramp-up between frames can never pop the curl
     * out of the frustum. Expressed as a fraction of page width.
     */
    const val MIN_CURL_FRACTION = 0.1f

    const val DEFAULT_COLS = 24
    const val DEFAULT_ROWS = 16

    /**
     * One-shot setup: must be called on the EGL thread after
     * [CurlGLRenderer.initialize] succeeded. Caches the program handle,
     * uniform locations and texture handles. The VBO is owned by
     * [CurlMeshBuffers] (bound before link by the renderer), and attribute
     * locations are the constants bound at link time.
     */
    fun setup(renderer: CurlGLRenderer, buffers: CurlMeshBuffers) {
        if (program != 0 || !renderer.isReady) return
        program = renderer.programHandle
        uMvp = GLES20.glGetUniformLocation(program, CurlShaderProgram.MVP_UNIFORM)
        uAxisPoint = GLES20.glGetUniformLocation(program, "uAxisPoint")
        uAxisNormal = GLES20.glGetUniformLocation(program, "uAxisNormal")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uFront = GLES20.glGetUniformLocation(program, "uFront")
        uBack = GLES20.glGetUniformLocation(program, "uBack")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uIsBack = GLES20.glGetUniformLocation(program, "uIsBack")
        uCreaseGain = GLES20.glGetUniformLocation(program, "uCreaseGain")
        uniformsReady = true

        frontTex = renderer.frontTextureHandle
        backTex = renderer.backTextureHandle
    }

    /** Uploads a Bitmap as one of the page textures. */
    fun uploadTexture(front: Bitmap, back: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frontTex)
        setTexParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, front, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTex)
        setTexParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, back, 0)
    }

    private fun setTexParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Draws one frame: front pass + back pass. [surfaceW]/[surfaceH] are the
     * actual surface pixel dimensions (C-4: the viewport must track the
     * surface, not the canonical page size); the page is centered inside it.
     */
    fun draw(
        params: CurlFrameParams,
        renderer: CurlGLRenderer,
        buffers: CurlMeshBuffers,
        surface: EGLSurface,
        surfaceW: Int,
        surfaceH: Int,
    ) {
        if (program == 0 || !uniformsReady) return
        GLES20.glUseProgram(program)
        // C-4: viewport in real surface pixels, not canonical page size.
        val vpW = surfaceW.coerceAtLeast(1)
        val vpH = surfaceH.coerceAtLeast(1)
        GLES20.glViewport(0, 0, vpW, vpH)

        buildMvp(params, vpW, vpH)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(uAxisPoint, params.axisPoint.x.toFloat(), params.axisPoint.y.toFloat())
        GLES20.glUniform2f(uAxisNormal, params.axisNormal.x.toFloat(), params.axisNormal.y.toFloat())
        GLES20.glUniform1f(uRadius, params.radius.toFloat())
        GLES20.glUniform3f(uLight, LIGHT_X, LIGHT_Y, LIGHT_Z)
        GLES20.glUniform1f(uCreaseGain, CurlShaderProgram.U_CREASE_GAIN)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frontTex)
        GLES20.glUniform1i(uFront, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTex)
        GLES20.glUniform1i(uBack, 1)

        GLES20.glBindBuffer(GlesConsts.ARRAY_BUFFER, buffers.bufferId)
        val stride = CurlMeshBuffers.BYTES_PER_VERTEX
        GLES20.glEnableVertexAttribArray(CurlShaderProgram.ATTR_POSITION)
        GLES20.glVertexAttribPointer(
            CurlShaderProgram.ATTR_POSITION, 3, GLES20.GL_FLOAT, false, stride, 0,
        )
        GLES20.glEnableVertexAttribArray(CurlShaderProgram.ATTR_UV)
        GLES20.glVertexAttribPointer(
            CurlShaderProgram.ATTR_UV, 2, GLES20.GL_FLOAT, false, stride,
            3 * 4,
        )

        val drawCount = buffers.drawCount

        // Front pass.
        GLES20.glUniform1f(uIsBack, 0f)
        GLES20.glUniform1f(uOffset, params.halfThickness.toFloat())
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, drawCount)

        // Back pass.
        GLES20.glUniform1f(uIsBack, 1f)
        GLES20.glUniform1f(uOffset, -params.halfThickness.toFloat())
        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, drawCount)

        GLES20.glDisableVertexAttribArray(CurlShaderProgram.ATTR_POSITION)
        GLES20.glDisableVertexAttribArray(CurlShaderProgram.ATTR_UV)
        GLES20.glBindBuffer(GlesConsts.ARRAY_BUFFER, 0)

        android.opengl.EGL14.eglSwapBuffers(
            android.opengl.EGL14.eglGetCurrentDisplay(),
            surface,
        )
    }

    /**
     * Builds the MVP via [CurlProjection] (pure Kotlin, JVM-testable): C-4
     * frustum covering the curl height; mesh +y (canonical page top) maps to
     * viewport top, so content stays upright (no y-flip).
     */
    private fun buildMvp(params: CurlFrameParams, vpW: Int, vpH: Int) {
        CurlProjection.mvp(params, vpW, vpH, mvp)
    }

    /** Frees cached handles; called from the EGL thread on teardown. */
    fun teardown() {
        program = 0
        uniformsReady = false
        frontTex = 0
        backTex = 0
    }

    /** Helper for tests: builds a direct native-order float buffer. */
    fun directFloatBuffer(count: Int): FloatBuffer =
        java.nio.ByteBuffer.allocateDirect(count * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
}
