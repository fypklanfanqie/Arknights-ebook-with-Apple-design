package com.lfq06.arknightsreader.turngl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurlMeshBuffersTest {
    @Test
    fun `float size per vertex is three positions and two uvs`() {
        assertEquals(5, CurlMeshBuffers.FLOATS_PER_VERTEX)
    }

    @Test
    fun `byte size for one vertex matches floats times four`() {
        assertEquals(CurlMeshBuffers.FLOATS_PER_VERTEX * 4, CurlMeshBuffers.BYTES_PER_VERTEX)
    }

    @Test
    fun `capacity math matches mesh budget`() {
        val cols = 80
        val rows = 48
        val maxVerts = com.lfq06.arknightsreader.turn.CurlMesh.maxVertexCount(cols, rows)
        assertEquals(maxVerts, CurlMeshBuffers.vertexCapacityFor(cols, rows))
        assertEquals(maxVerts * CurlMeshBuffers.FLOATS_PER_VERTEX, CurlMeshBuffers.floatCountFor(cols, rows))
    }

    @Test
    fun `clamps degenerate grid sizes to a one-by-one budget`() {
        val v = CurlMeshBuffers.vertexCapacityFor(0, -3)
        assertTrue(v >= com.lfq06.arknightsreader.turn.CurlMesh.maxVertexCount(1, 1))
    }
}
