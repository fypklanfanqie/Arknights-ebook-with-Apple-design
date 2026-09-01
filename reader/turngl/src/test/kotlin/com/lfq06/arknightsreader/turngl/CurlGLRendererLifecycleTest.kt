package com.lfq06.arknightsreader.turngl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * State-machine tests for the renderer lifecycle. GL calls are faked through
 * [GlesProxy]; the real GPU path is verified on-device only.
 */
class CurlGLRendererLifecycleTest {
    private class RecordingProxy : GlesProxy {
        val calls = mutableListOf<String>()
        var compiled = true
        var linked = true

        override fun glCreateShader(type: Int): Int {
            calls += "createShader"
            return 1
        }

        override fun glShaderSource(shader: Int, source: String) {
            calls += "shaderSource"
        }

        override fun glCompileShader(shader: Int) {
            calls += "compileShader"
        }

        override fun glGetShaderiv(shader: Int, pname: Int, params: IntArray) {
            calls += "getShaderiv"
            params[0] = if (compiled) 1 else 0
        }

        override fun glGetShaderInfoLog(shader: Int): String = ""

        override fun glCreateProgram(): Int {
            calls += "createProgram"
            return 2
        }

        override fun glAttachShader(program: Int, shader: Int) {
            calls += "attachShader"
        }

        override fun glLinkProgram(program: Int) {
            calls += "linkProgram"
        }

        override fun glGetProgramiv(program: Int, pname: Int, params: IntArray) {
            calls += "getProgramiv"
            params[0] = if (linked) 1 else 0
        }

        override fun glGetProgramInfoLog(program: Int): String = ""

        override fun glDeleteShader(shader: Int) {
            calls += "deleteShader"
        }

        override fun glDeleteProgram(program: Int) {
            calls += "deleteProgram"
        }

        override fun glGenTextures(textures: IntArray) {
            calls += "genTextures"
            textures[0] = 10
            textures[1] = 11
        }

        override fun glDeleteTextures(textures: IntArray) {
            calls += "deleteTextures"
        }

        override fun glClearColor(r: Float, g: Float, b: Float, a: Float) {
            calls += "clearColor"
        }

        override fun glEnable(cap: Int) {
            calls += "enable"
        }

        override fun glDisable(cap: Int) {
            calls += "disable"
        }

        override fun glViewport(x: Int, y: Int, w: Int, h: Int) {
            calls += "viewport"
        }
    }

    @Test
    fun `fresh renderer is uninitialized and release is a no-op`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        assertEquals(CurlGLRenderer.Lifecycle.NEW, renderer.lifecycle)
        renderer.release()
        renderer.release()
        assertEquals(CurlGLRenderer.Lifecycle.RELEASED, renderer.lifecycle)
        assertTrue(proxy.calls.isEmpty(), "no GL calls before initialize: ${proxy.calls}")
    }

    @Test
    fun `initialize compiles both shaders and links the program`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        renderer.initialize()
        assertEquals(CurlGLRenderer.Lifecycle.READY, renderer.lifecycle)
        assertTrue("compileShader" in proxy.calls)
        assertTrue("linkProgram" in proxy.calls)
        assertEquals(2, proxy.calls.count { it == "deleteShader" }, "intermediate shaders are freed")
    }

    @Test
    fun `initialize after release is rejected`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        renderer.initialize()
        renderer.release()
        val before = proxy.calls.size
        renderer.initialize()
        assertEquals(CurlGLRenderer.Lifecycle.RELEASED, renderer.lifecycle)
        assertEquals(before, proxy.calls.size, "no GL calls after release")
    }

    @Test
    fun `release is idempotent and deletes program and textures`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        renderer.initialize()
        val afterInit = proxy.calls.size
        renderer.release()
        renderer.release()
        assertEquals(CurlGLRenderer.Lifecycle.RELEASED, renderer.lifecycle)
        assertEquals(afterInit + 2, proxy.calls.size, "second release adds no GL calls")
        assertTrue("deleteProgram" in proxy.calls)
        assertTrue("deleteTextures" in proxy.calls)
    }

    @Test
    fun `failed shader compile moves to error and release cleans up`() {
        val proxy = RecordingProxy().apply { compiled = false }
        val renderer = CurlGLRenderer(proxy)
        renderer.initialize()
        assertEquals(CurlGLRenderer.Lifecycle.ERROR, renderer.lifecycle)
        renderer.release()
        assertEquals(CurlGLRenderer.Lifecycle.RELEASED, renderer.lifecycle)
    }

    @Test
    fun `failed link moves to error`() {
        val proxy = RecordingProxy().apply { linked = false }
        val renderer = CurlGLRenderer(proxy)
        renderer.initialize()
        assertEquals(CurlGLRenderer.Lifecycle.ERROR, renderer.lifecycle)
    }

    @Test
    fun `render before initialize is a safe no-op`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        renderer.render(CurlFrameParams.idle())
        assertEquals(CurlGLRenderer.Lifecycle.NEW, renderer.lifecycle)
        assertTrue("clearColor" !in proxy.calls)
    }

    @Test
    fun `lifecycle transitions are recorded in order`() {
        val proxy = RecordingProxy()
        val renderer = CurlGLRenderer(proxy)
        assertFalse(renderer.isReady)
        renderer.initialize()
        assertTrue(renderer.isReady)
        renderer.release()
        assertFalse(renderer.isReady)
        assertSame(CurlGLRenderer.Lifecycle.RELEASED, renderer.lifecycle, "lifecycle must end released")
    }
}
