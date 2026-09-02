package com.lfq06.arknightsreader.turngl

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I-6 contract: after a destroy cycle (host stop -> renderer/buffers
 * released), a new surface-available cycle must rebuild the renderer and
 * buffers instead of reusing released GL handles.
 */
class CurlTextureViewHostReuseTest {

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

    @Test
    fun `renderer and buffers are rebuilt after release`() {
        val proxy = FakeProxy()
        var rendererBuilds = 0
        var buffersBuilds = 0
        val host = CurlTextureViewHost(
            rendererFactory = { rendererBuilds++; CurlGLRenderer(proxy) },
            buffersFactory = { buffersBuilds++; CurlMeshBuffers(proxy) },
        )
        // Simulate the previous cycle having run and been torn down.
        host.rendererForTest().release()
        assertTrue(host.rendererForTest().isReleased, "precondition: renderer released")

        // start() with a "new surface": must detect the released renderer and
        // rebuild both factories (I-6). The real loop runs on a thread, so we
        // assert on the factory counters after the rebuild decision — the
        // rebuild happens synchronously inside start() before the thread
        // spawns, and the worker thread dies fast without a real EGL display.
        try {
            host.start(android.graphics.SurfaceTexture(0), 1080, 1920)
        } catch (_: Throwable) {
            // JVM: EGL14/loop internals throw; the synchronous rebuild already
            // happened before the thread body runs.
        }
        host.stop()
        assertEquals(2, rendererBuilds, "released renderer must be rebuilt on start")
        assertEquals(2, buffersBuilds, "buffers must be rebuilt together with the renderer")
    }

    @Test
    fun `renderer is not rebuilt while still alive`() {
        val proxy = FakeProxy()
        var rendererBuilds = 0
        val host = CurlTextureViewHost(
            rendererFactory = { rendererBuilds++; CurlGLRenderer(proxy) },
        )
        // Fresh renderer (NEW state) must not trigger a rebuild.
        try {
            host.start(android.graphics.SurfaceTexture(0), 1080, 1920)
        } catch (_: Throwable) {
        }
        host.stop()
        assertEquals(1, rendererBuilds, "a live renderer must be reused across start")
    }

    @Test
    fun `loop teardown drops the cached program handles (I-6 end-to-end)`() {
        // The second lifecycle must not early-return in CurlEglFrame.setup
        // because of a program handle cached from the first context. Lock the
        // wiring at source level: the render loop's exit path calls teardown.
        val src = java.io.File("src/main/kotlin/com/lfq06/arknightsreader/turngl/CurlTextureViewHost.kt")
            .readText()
        val loopStart = src.indexOf("private fun loop(")
        val loopEnd = src.indexOf("private fun tick(")
        assertTrue(loopStart in 0 until loopEnd, "loop() must exist before tick()")
        val loopBody = src.substring(loopStart, loopEnd)
        assertTrue(
            Regex("CurlEglFrame\\.teardown\\(\\)").containsMatchIn(loopBody),
            "loop exit must call CurlEglFrame.teardown() so a second lifecycle gets fresh handles",
        )
        val teardownAfterRelease = loopBody.indexOf("CurlEglFrame.teardown()") > loopBody.indexOf("renderer.release()")
        assertTrue(teardownAfterRelease, "teardown must run after the GL resources are released")
    }

    @Test
    fun `legacy pending mesh channel is gone (single writer, I-4)`() {
        val src = java.io.File("src/main/kotlin/com/lfq06/arknightsreader/turngl/CurlTextureViewHost.kt")
            .readText()
        assertTrue(!src.contains("consumePendingMesh"), "legacy pending-mesh queue must be removed (single-writer contract)")
        assertTrue(!src.contains("fun updateMesh"), "legacy updateMesh must be removed; only render-thread uploadMesh remains")
    }
}
