package com.lfq06.arknightsreader.turn

/**
 * Result of mapping one page point through the curl deformation.
 *
 * (x, y, z) is the deformed position in canonical page space and
 * (nx, ny, nz) is the outward surface normal. [region] classifies which
 * developable segment the point landed on.
 */
data class DeformedPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val nx: Double,
    val ny: Double,
    val nz: Double,
    val region: Region,
) {
    enum class Region { FLAT_FRONT, CYLINDRICAL_WRAP, FLAT_BACK }

    fun isFinite(): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite() &&
            nx.isFinite() && ny.isFinite() && nz.isFinite()
}
