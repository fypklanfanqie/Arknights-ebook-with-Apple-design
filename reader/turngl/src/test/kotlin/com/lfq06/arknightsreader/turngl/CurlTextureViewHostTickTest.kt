package com.lfq06.arknightsreader.turngl

import com.lfq06.arknightsreader.turn.Vec2
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM contract for the host tick (C-3): the frame drawn is the params the
 * FrameListener RETURNS this tick, never a stale cached snapshot. Runs
 * without EGL via the host's drawOverride seam; mesh staging goes through a
 * proxy-backed CurlMeshBuffers, so no real GL is touched.
 */
class CurlTextureViewHostTickTest {

    /** Full no-op proxy (only buffer ids are of interest here). */
    private class FakeProxy : GlesProxy {
        override fun glCreateShader(type: Int): Int = 1
        override fun glShaderSource(shader: Int, source: String) {}
        override fun glCompileShader(shader: Int) {}
        override fun glGetShaderiv(shader: Int, pname: Int, params: IntArray) {
            params[0] = 1
        }
        override fun glGetShaderInfoLog(shader: Int): String = ""
        override fun glCreateProgram(): Int = 2
        override fun glAttachShader(program: Int, shader: Int) {}
        override fun glLinkProgram(program: Int) {}
        override fun glGetProgramiv(program: Int, pname: Int, params: IntArray) {
            params[0] = 1
        }
        override fun glGetProgramInfoLog(program: Int): String = ""
        override fun glDeleteShader(shader: Int) {}
        override fun glDeleteProgram(program: Int) {}
        override fun glGenTextures(textures: IntArray) {
            for (i in textures.indices) textures[i] = 10 + i
        }
        override fun glDeleteTextures(textures: IntArray) {}
        override fun glClearColor(r: Float, g: Float, b: Float, a: Float) {}
        override fun glEnable(cap: Int) {}
        override fun glDisable(cap: Int) {}
        override fun glViewport(x: Int, y: Int, w: Int, h: Int) {}
        override fun glBindAttribLocation(program: Int, index: Int, name: String) {}
        override fun glGenBuffers(buffers: IntArray) {
            for (i in buffers.indices) buffers[i] = 30 + i
        }
        override fun glDeleteBuffers(buffers: IntArray) {}
        override fun glBindBuffer(target: Int, buffer: Int) {}
        override fun glBufferData(target: Int, size: Int, data: FloatBuffer?, usage: Int) {}
        override fun glBufferSubData(target: Int, offset: Int, size: Int, data: FloatBuffer) {}
    }

    private fun hostWith(
        drawn: MutableList<CurlFrameParams>,
        listener: () -> CurlFrameParams?,
    ): CurlTextureViewHost {
        val proxy = FakeProxy()
        return CurlTextureViewHost(
            rendererFactory = { CurlGLRenderer(proxy) },
            buffersFactory = { CurlMeshBuffers(proxy) },
            drawOverride = { params, _ -> drawn += params },
        ).apply { frameListener = CurlTextureViewHost.FrameListener { listener() } }
    }

    private val fresh = CurlFrameParams(
        axisPoint = Vec2(210.0, 0.0),
        axisNormal = Vec2(1.0, 0.0),
        radius = 21.0,
        pageW = 420.0,
        pageH = 560.0,
        pageVisible = true,
    )

    @Test
    fun `tick draws the params returned by the frame listener`() {
        val stale = CurlFrameParams.idle()
        val drawn = mutableListOf<CurlFrameParams>()
        var nextReturn: CurlFrameParams? = fresh
        val host = hostWith(drawn) { nextReturn }
        // Stale snapshot in the cache; the listener returns a NEW one.
        host.setFrameParams(stale)
        host.setDirty(true)

        assertTrue(host.tickForTest(), "dirty host must draw")
        assertEquals(listOf(fresh), drawn, "draw must receive the LISTENER-returned params (C-3), not the cached stale ones")
    }

    @Test
    fun `settle lerp result reaches the draw in the same tick`() {
        // Simulates the settle path: each prepare computes a NEW lerped
        // snapshot; the draw must see exactly that snapshot (C-3).
        val drawn = mutableListOf<CurlFrameParams>()
        var tick = 0
        val host = hostWith(drawn) {
            tick++
            fresh.copy(radius = fresh.radius * tick)
        }
        host.setDirty(true)
        assertTrue(host.tickForTest())
        assertTrue(host.tickForTest())
        assertEquals(
            listOf(fresh.copy(radius = 21.0), fresh.copy(radius = 42.0)),
            drawn,
            "each tick must draw its own freshly returned params",
        )
    }

    @Test
    fun `listener returning null skips the draw`() {
        val drawn = mutableListOf<CurlFrameParams>()
        val host = hostWith(drawn) { null }
        host.setDirty(true)
        assertTrue(!host.tickForTest(), "null prepare result must skip drawing")
        assertTrue(drawn.isEmpty())
    }

    @Test
    fun `quiescent host does not draw`() {
        val drawn = mutableListOf<CurlFrameParams>()
        val host = hostWith(drawn) { fresh }
        assertTrue(!host.tickForTest(), "no dirty flag and no pending request: no draw")
        assertTrue(drawn.isEmpty())
    }
}
