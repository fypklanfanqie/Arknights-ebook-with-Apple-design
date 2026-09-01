package com.lfq06.arknightsreader.turngl

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Stateful draw helpers bound to one EGL context. Split from
 * [CurlTextureViewHost] so the host only owns threading/EGL and every GLES
 * call in the draw path lives here.
 *
 * Draw model per frame (matches the reference engine's material split):
 * 1. Front pass: cull back faces, uIsBack = 0, offset = +halfThickness.
 * 2. Back pass: cull front faces, uIsBack = 1, offset = -halfThickness.
 * Both passes share the same VBO geometry; the sub-pixel normal offset keeps
 * the two sheets from fighting over the same depth.
 */
object CurlEglFrame {

    private var program = 0
    private var uniformsReady = false
    private var attribPosition = 0
    private var attribUv = 0
    private var uMvp = 0
    private var uAxisPoint = 0
    private var uAxisNormal = 0
    private var uRadius = 0
    private var uPageW = 0
    private var uPageH = 0
    private var uOffset = 0
    private var uFront = 0
    private var uBack = 0
    private var uLight = 0
    private var uIsBack = 0
    private var uCreaseGain = 0

    private var frontTex = 0
    private var backTex = 0
    private var vbo = 0

    private val mvp = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)

    private const val LIGHT_X = 0.25f
    private const val LIGHT_Y = -0.35f
    private const val LIGHT_Z = 0.9f

    /**
     * One-shot setup: must be called on the EGL thread after
     * [CurlGLRenderer.initialize] succeeded. Builds the program, caches
     * uniform/attribute locations, and creates the VBO.
     */
    fun setup(renderer: CurlGLRenderer, buffers: CurlMeshBuffers) {
        if (program != 0 || !renderer.isReady) return
        program = renderer.programHandle
        attribPosition = GLES20.glGetAttribLocation(program, "position")
        attribUv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glBindAttribLocation(program, CurlShaderProgram.ATTR_POSITION, "position")
        GLES20.glBindAttribLocation(program, CurlShaderProgram.ATTR_UV, "uv")
        uMvp = GLES20.glGetUniformLocation(program, CurlShaderProgram.MVP_UNIFORM)
        uAxisPoint = GLES20.glGetUniformLocation(program, "uAxisPoint")
        uAxisNormal = GLES20.glGetUniformLocation(program, "uAxisNormal")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uPageW = GLES20.glGetUniformLocation(program, "uPageW")
        uPageH = GLES20.glGetUniformLocation(program, "uPageH")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uFront = GLES20.glGetUniformLocation(program, "uFront")
        uBack = GLES20.glGetUniformLocation(program, "uBack")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uIsBack = GLES20.glGetUniformLocation(program, "uIsBack")
        uCreaseGain = GLES20.glGetUniformLocation(program, "uCreaseGain")
        uniformsReady = true

        frontTex = renderer.frontTextureHandle
        backTex = renderer.backTextureHandle

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val floatCount = CurlMeshBuffers.floatCountFor(DEFAULT_COLS, DEFAULT_ROWS)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            floatCount * 4,
            null,
            GLES20.GL_DYNAMIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
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

    /** Streams a built mesh result into the VBO. */
    fun updateMesh(buffers: CurlMeshBuffers, result: com.lfq06.arknightsreader.turn.CurlMesh.MeshResult) {
        if (vbo == 0 || result.vertexCount <= 0) return
        val floats = FloatBuffer.allocate(result.vertexCount * CurlMeshBuffers.FLOATS_PER_VERTEX)
        for (i in 0 until result.vertexCount) {
            floats.put(result.positions[i * 3])
            floats.put(result.positions[i * 3 + 1])
            floats.put(result.positions[i * 3 + 2])
            floats.put(result.uvs[i * 2])
            floats.put(result.uvs[i * 2 + 1])
        }
        floats.flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER,
            0,
            result.vertexCount * CurlMeshBuffers.BYTES_PER_VERTEX,
            floats,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Draws one frame: front pass + back pass. */
    fun draw(params: CurlFrameParams, renderer: CurlGLRenderer, buffers: CurlMeshBuffers, surface: EGLSurface) {
        if (program == 0 || !uniformsReady) return
        GLES20.glUseProgram(program)
        GLES20.glViewport(0, 0, params.pageW.toInt().coerceAtLeast(1), params.pageH.toInt().coerceAtLeast(1))

        // Orthographic-ish camera: the pipeline works in pixel units, so the
        // projection just maps page pixels to clip space (flip y for GL).
        val w = params.pageW.toFloat().coerceAtLeast(1f)
        val h = params.pageH.toFloat().coerceAtLeast(1f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0f)
        val near = 2f
        val far = 4f
        val left = -w / 2f
        val right = w / 2f
        val bottom = -h / 2f
        val top = h / 2f
        // Column-major ortho with y flip handled by the view matrix above.
        Matrix.frustumM(proj, 0, left * 0.9f, right * 0.9f, bottom * 0.9f, top * 0.9f, near, far)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(uAxisPoint, params.axisPoint.x.toFloat(), params.axisPoint.y.toFloat())
        GLES20.glUniform2f(uAxisNormal, params.axisNormal.x.toFloat(), params.axisNormal.y.toFloat())
        GLES20.glUniform1f(uRadius, params.radius.toFloat())
        GLES20.glUniform1f(uPageW, params.pageW.toFloat())
        GLES20.glUniform1f(uPageH, params.pageH.toFloat())
        GLES20.glUniform3f(uLight, LIGHT_X, LIGHT_Y, LIGHT_Z)
        GLES20.glUniform1f(uCreaseGain, CurlShaderProgram.U_CREASE_GAIN)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frontTex)
        GLES20.glUniform1i(uFront, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTex)
        GLES20.glUniform1i(uBack, 1)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
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

        // Front pass.
        GLES20.glUniform1f(uIsBack, 0f)
        GLES20.glUniform1f(uOffset, params.halfThickness.toFloat())
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffers.drawCount)

        // Back pass.
        GLES20.glUniform1f(uIsBack, 1f)
        GLES20.glUniform1f(uOffset, -params.halfThickness.toFloat())
        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffers.drawCount)

        GLES20.glDisableVertexAttribArray(CurlShaderProgram.ATTR_POSITION)
        GLES20.glDisableVertexAttribArray(CurlShaderProgram.ATTR_UV)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        android.opengl.EGL14.eglSwapBuffers(
            android.opengl.EGL14.eglGetCurrentDisplay(),
            surface,
        )
    }

    /** Frees VBO and clears cached handles; called from the EGL thread on teardown. */
    fun teardown() {
        if (vbo != 0) {
            val ids = intArrayOf(vbo)
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            vbo = 0
        }
        program = 0
        uniformsReady = false
        frontTex = 0
        backTex = 0
    }

    const val DEFAULT_COLS = 24
    const val DEFAULT_ROWS = 16

    /** Helper for tests: builds a direct native-order float buffer. */
    fun directFloatBuffer(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
