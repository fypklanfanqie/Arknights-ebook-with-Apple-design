package com.lfq06.arknightsreader.turngl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * CPU-side mirror of the GLSL deformation math in [CurlShaderProgram]. Exists
 * so the shader's piecewise mapping can be verified numerically against
 * [com.lfq06.arknightsreader.turn.CurlSolver.deformPoint] in a plain JVM test,
 * and so host-side code can evaluate the same mapping without a GL context.
 *
 * THE CONTRACT: any change to the GLSL in [CurlShaderProgram] must be mirrored
 * here (and vice versa); `CurlShaderMathTest` fails if the two drift apart.
 */
object CurlShaderMath {
    const val FOLD_RADIUS_EPS = 1e-4
    const val PI_F = PI

    /** Maps one material-space point through the same three-branch rule as the vertex shader. */
    fun deform(
        px: Double,
        py: Double,
        axisX: Double,
        axisY: Double,
        nx: Double,
        ny: Double,
        radius: Double,
    ): Deformed {
        val len = hypot(nx, ny)
        val nnx = if (len.isFinite() && len > 1e-9) nx / len else 1.0
        val nny = if (len.isFinite() && len > 1e-9) ny / len else 0.0
        val tx = -nny
        val ty = nnx
        val r = maxOf(0.0, radius)
        val relX = px - axisX
        val relY = py - axisY
        val d = relX * nnx + relY * nny
        val s = relX * tx + relY * ty

        val lat: Double
        val z: Double
        val nz: Double
        val outX: Double
        val outY: Double
        when {
            d <= 0.0 -> {
                lat = d; z = 0.0; nz = 1.0
            }
            r >= FOLD_RADIUS_EPS && d < PI * r -> {
                val ang = d / r
                lat = r * sin(ang)
                z = r * (1.0 - cos(ang))
                nz = cos(ang)
            }
            else -> {
                lat = -(d - PI * r)
                z = 2.0 * r
                nz = -1.0
            }
        }
        outX = axisX + tx * s + nnx * lat
        outY = axisY + ty * s + nny * lat
        val deformNx = when {
            d <= 0.0 -> 0.0
            r >= FOLD_RADIUS_EPS && d < PI * r -> -sin(d / r) * nnx
            else -> 0.0
        }
        val deformNy = when {
            d <= 0.0 -> 0.0
            r >= FOLD_RADIUS_EPS && d < cylindricalHi(r) -> -sin(d / r) * nny
            else -> 0.0
        }
        return Deformed(outX, outY, z, deformNx, deformNy, nz)
    }

    private fun cylindricalHi(r: Double): Double = PI * r

    /** CPU mirror of the vertex-shader output position and normal. */
    data class Deformed(val x: Double, val y: Double, val z: Double, val nx: Double, val ny: Double, val nz: Double)
}
