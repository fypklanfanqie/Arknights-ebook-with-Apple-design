package com.lfq06.arknightsreader.turngl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-contract tests for the embedded GLSL. The renderer cannot run in a
 * JVM test, so these assertions lock down the shader source: required
 * uniforms/attributes/varyings, the three deformation branches, the back-face
 * UV mirror, and the half-thickness offset. Any edit to the GLSL that breaks
 * the documented pipeline contract fails here.
 */
class CurlShaderProgramSourceTest {
    private val vert = CurlShaderProgram.VERTEX_SHADER
    private val frag = CurlShaderProgram.FRAGMENT_SHADER

    @Test
    fun `vertex shader declares the documented uniforms`() {
        for (name in listOf(
            "uAxisPoint", "uAxisNormal", "uRadius", "uPageW", "uPageH", "uOffset",
        )) {
            assertTrue(Regex("uniform\\s+\\w+\\s+$name\\b").containsMatchIn(vert), "missing uniform $name")
        }
    }

    @Test
    fun `vertex shader declares position and uv attributes`() {
        assertTrue(Regex("attribute\\s+\\w+\\s+position\\b").containsMatchIn(vert))
        assertTrue(Regex("attribute\\s+\\w+\\s+uv\\b").containsMatchIn(vert))
    }

    @Test
    fun `vertex shader carries the three deformation branches`() {
        // Flat front: d <= 0
        assertTrue(vert.contains("d <= 0.0"), "flat-front branch missing")
        // Cylindrical wrap: r >= eps && d < PI * r
        assertTrue(Regex("r\\s*>=\\s*1e-4\\s*&&\\s*d\\s*<\\s*PI \\* r").containsMatchIn(vert), "cylinder branch missing")
        // Flat back: lat = -(d - PI * r), z = 2 * r
        assertTrue(Regex("lat\\s*=\\s*-\\(d\\s*-\\s*PI \\* r\\)").containsMatchIn(vert), "flat-back lat missing")
        assertTrue(Regex("z\\s*=\\s*2\\.0 \\* r").containsMatchIn(vert), "flat-back z missing")
        // Cylinder mapping: lat = r * sin(ang), z = r * (1 - cos(ang))
        assertTrue(Regex("lat\\s*=\\s*r \\* sin\\(ang\\)").containsMatchIn(vert), "cylinder lat missing")
        assertTrue(Regex("z\\s*=\\s*r \\* \\(1\\.0 - cos\\(ang\\)\\)").containsMatchIn(vert), "cylinder z missing")
    }

    @Test
    fun `vertex shader offsets by the half-thickness normal`() {
        assertTrue(Regex("pos\\s*\\+=\\s*nrm \\* uOffset").containsMatchIn(vert), "half-thickness offset missing")
    }

    @Test
    fun `vertex shader outputs uv normal and crease varyings`() {
        for (name in listOf("vUv", "vNrm", "vCrease")) {
            assertTrue(Regex("varying\\s+\\w+\\s+$name\\b").containsMatchIn(vert), "missing varying $name in vertex")
            assertTrue(Regex("varying\\s+\\w+\\s+$name\\b").containsMatchIn(frag), "missing varying $name in fragment")
        }
    }

    @Test
    fun `fragment shader samples both textures with mirrored back uv`() {
        assertTrue(Regex("uniform\\s+sampler2D\\s+uFront\\b").containsMatchIn(frag))
        assertTrue(Regex("uniform\\s+sampler2D\\s+uBack\\b").containsMatchIn(frag))
        // Back face mirrors u horizontally.
        assertTrue(frag.contains("1.0 - vUv.x"), "back-face UV mirror missing")
        // uIsBack selects the material.
        assertTrue(Regex("uniform\\s+float\\s+uIsBack\\b").containsMatchIn(frag))
    }

    @Test
    fun `fragment shader applies diffuse lighting and crease shading`() {
        assertTrue(Regex("dot\\(N,\\s*normalize\\(uLight\\)\\)").containsMatchIn(frag), "diffuse lighting missing")
        assertTrue(Regex("uniform\\s+vec3\\s+uLight\\b").containsMatchIn(frag))
        assertTrue(frag.contains("vCrease"), "crease shading missing")
    }

    @Test
    fun `shaders declare es precision`() {
        assertTrue(frag.contains("precision highp float"), "fragment precision missing")
    }

    @Test
    fun `attribute locations are stable`() {
        assertEquals(0, CurlShaderProgram.ATTR_POSITION)
        assertEquals(1, CurlShaderProgram.ATTR_UV)
    }
}
