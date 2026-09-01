package com.lfq06.arknightsreader.turngl

import com.lfq06.arknightsreader.turn.CurlMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-side storage for one curl mesh. Interleaves position (3 floats) and uv
 * (2 floats) into a single VBO; [setCurl] streams updated vertices with
 * glBufferSubData, so a drag never reallocates GPU memory.
 *
 * Interleaved layout per vertex: [x, y, z, u, v].
 */
class CurlMeshBuffers(private val gl: GlesProxy = RealGles) {
    private var bufferId = 0
    private var capacityVertices = 0
    private var staged: FloatBuffer? = null
    private var stagedDirty = false

    /** Vertices to draw this frame (valid after [setCurl]). */
    var drawCount = 0
        private set

    val isInitialized: Boolean get() = bufferId != 0

    /**
     * Allocates the VBO for the worst-case mesh of a cols x rows grid. Safe to
     * call again with a larger grid: reallocates only when capacity grows.
     */
    fun ensureCapacity(cols: Int, rows: Int) {
        val wanted = vertexCapacityFor(cols, rows)
        if (bufferId != 0 && wanted <= capacityVertices) return
        capacityVertices = maxOf(wanted, MIN_CAPACITY_VERTICES)
        if (bufferId == 0) {
            val ids = IntArray(1)
            bufferId = ids[0]
        }
        staged = ByteBuffer
            .allocateDirect(capacityVertices * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        stagedDirty = true
        drawCount = 0
    }

    /**
     * Uploads [result] (positions + uvs from [CurlMesh.build]) into the VBO.
     * Reuses the staged CPU buffer; only calls glBufferSubData on the GPU path.
     */
    fun setCurl(result: CurlMesh.MeshResult) {
        val buf = staged ?: return
        buf.clear()
        val n = result.vertexCount
        for (i in 0 until n) {
            buf.put(result.positions[i * 3])
            buf.put(result.positions[i * 3 + 1])
            buf.put(result.positions[i * 3 + 2])
            buf.put(result.uvs[i * 2])
            buf.put(result.uvs[i * 2 + 1])
        }
        buf.flip()
        drawCount = n
        stagedDirty = true
    }

    /** Releases the VBO; safe to call twice. */
    fun release() {
        bufferId = 0
        capacityVertices = 0
        staged = null
        drawCount = 0
        stagedDirty = false
    }

    companion object {
        const val FLOATS_PER_VERTEX = 5
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4
        const val MIN_CAPACITY_VERTICES = 64

        /** Worst-case vertex budget for a cols x rows grid (mirrors CurlMesh). */
        fun vertexCapacityFor(cols: Int, rows: Int): Int = CurlMesh.maxVertexCount(cols, rows)

        /** Float count implied by a vertex capacity. */
        fun floatCountFor(cols: Int, rows: Int): Int = vertexCapacityFor(cols, rows) * FLOATS_PER_VERTEX
    }
}
