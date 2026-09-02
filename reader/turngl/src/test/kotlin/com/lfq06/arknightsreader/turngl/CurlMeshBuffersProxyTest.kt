package com.lfq06.arknightsreader.turngl

import com.lfq06.arknightsreader.turn.CurlMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proxy-driven tests for the VBO staging path (C-2 / I-2): ensureCapacity must
 * actually glGenBuffers, setCurl must stream vertices with glBufferSubData and
 * keep drawCount live, and release must delete the GL buffer. The recording
 * proxy proves the GL call sequence without a device.
 */
class CurlMeshBuffersProxyTest {

    /** Recording proxy: hands out increasing buffer ids and records calls. */
    private class RecordingProxy : GlesProxy {
        val calls = mutableListOf<String>()
        var nextBufferId = 7
        val bufferIds = mutableListOf<Int>()

        // ---- lifecycle (renderer) stubs: unused here ----
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
        override fun glGenTextures(textures: IntArray) {}
        override fun glDeleteTextures(textures: IntArray) {}
        override fun glClearColor(r: Float, g: Float, b: Float, a: Float) {}
        override fun glEnable(cap: Int) {}
        override fun glDisable(cap: Int) {}
        override fun glViewport(x: Int, y: Int, w: Int, h: Int) {}
        override fun glBindAttribLocation(program: Int, index: Int, name: String) {}

        // ---- buffers under test ----
        override fun glGenBuffers(buffers: IntArray) {
            calls += "genBuffers"
            for (i in buffers.indices) {
                buffers[i] = nextBufferId++
                bufferIds += buffers[i]
            }
        }

        override fun glDeleteBuffers(buffers: IntArray) {
            calls += "deleteBuffers:${buffers.toList()}"
        }

        override fun glBindBuffer(target: Int, buffer: Int) {
            calls += "bindBuffer:$buffer"
        }

        override fun glBufferData(target: Int, size: Int, data: FloatBuffer?, usage: Int) {
            calls += "bufferData:$size"
        }

        override fun glBufferSubData(target: Int, offset: Int, size: Int, data: FloatBuffer) {
            calls += "bufferSubData:$size"
        }
    }

    /** Builds a MeshResult holding `count` vertices with known values. */
    private fun meshResult(count: Int): CurlMesh.MeshResult {
        val positions = FloatArray(count * 3) { it.toFloat() }
        val uvs = FloatArray(count * 2) { it.toFloat() }
        return CurlMesh.MeshResult(count, positions, uvs)
    }

    @Test
    fun `ensureCapacity generates a real buffer id`() {
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        buffers.ensureCapacity(24, 16)
        assertTrue(buffers.isInitialized, "ensureCapacity must glGenBuffers (C-2: bufferId was always 0)")
        assertEquals(listOf<Int>(7), gl.bufferIds, "buffer id must come from the proxy")
        assertTrue("genBuffers" in gl.calls)
    }

    @Test
    fun `setCurl streams vertices and updates drawCount`() {
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        buffers.ensureCapacity(24, 16)
        gl.calls.clear()
        val result = meshResult(120)
        buffers.setCurl(result)
        assertEquals(120, buffers.drawCount, "setCurl must make drawCount live (C-2: drawCount stayed 0)")
        val bytes = 120 * CurlMeshBuffers.BYTES_PER_VERTEX
        assertTrue(gl.calls.any { it == "bufferSubData:$bytes" }, "setCurl must upload via glBufferSubData")
    }

    @Test
    fun `capacity growth reallocates with a new buffer`() {
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        buffers.ensureCapacity(4, 4)
        buffers.ensureCapacity(24, 16)
        assertEquals(2, gl.calls.count { it == "genBuffers" }, "growth must re-gen a larger buffer")
        // The old VBO must not leak on growth.
        assertTrue(gl.calls.any { it.startsWith("deleteBuffers:[7]") }, "growth must delete the old GL buffer")
        // Worst-case budget of the larger grid must fit.
        val budget = CurlMeshBuffers.vertexCapacityFor(24, 16)
        val big = meshResult(budget)
        buffers.setCurl(big)
        assertEquals(budget, buffers.drawCount)
    }

    @Test
    fun `capacity reuse does not regen for equal or smaller grids`() {
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        buffers.ensureCapacity(24, 16)
        val genCount = gl.calls.count { it == "genBuffers" }
        buffers.ensureCapacity(24, 16)
        buffers.ensureCapacity(8, 8)
        assertEquals(genCount, gl.calls.count { it == "genBuffers" }, "non-growing capacity must reuse the VBO")
    }

    @Test
    fun `release deletes the GL buffer and clears state`() {
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        buffers.ensureCapacity(24, 16)
        buffers.setCurl(meshResult(10))
        buffers.release()
        assertEquals(0, buffers.drawCount)
        assertTrue(gl.calls.any { it.startsWith("deleteBuffers:[7]") }, "release must glDeleteBuffers the staged id")
        assertTrue(!buffers.isInitialized)
    }

    @Test
    fun `worst-case mesh fits the staged capacity`() {
        // The seam-aligned clipper can emit up to 24 verts per base triangle;
        // a full worst-case build must not overflow the staged buffer.
        val gl = RecordingProxy()
        val buffers = CurlMeshBuffers(gl)
        val cols = 24
        val rows = 16
        buffers.ensureCapacity(cols, rows)
        // Build a real diagonal curl that crosses both seams to stress clipping.
        val out = CurlMesh.allocOutput(cols, rows)
        val result = CurlMesh.build(
            pageW = 420.0,
            pageH = 560.0,
            originX = 0.0,
            cols = cols,
            rows = rows,
            axisPoint = CurlMesh.canonicalToMeshPoint(com.lfq06.arknightsreader.turn.Vec2(210.0, 0.0), 1, 0.0, 420.0),
            axisNormal = CurlMesh.canonicalToMeshVector(com.lfq06.arknightsreader.turn.Vec2(0.707, 0.707), 1),
            radius = 21.0,
            output = out,
        )
        assertTrue(result.vertexCount > 0)
        buffers.setCurl(result)
        assertEquals(result.vertexCount, buffers.drawCount)
        val budget = CurlMeshBuffers.vertexCapacityFor(cols, rows)
        assertTrue(result.vertexCount <= budget, "real build must fit the worst-case budget")
    }
}
