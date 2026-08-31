package com.lfq06.arknightsreader.turn

/**
 * A drag-velocity reading. [ageMs] is how long ago the sample was taken;
 * samples older than 120 ms are treated as stale (value 0).
 */
data class VelocitySample(
    val value: Double,
    val ageMs: Double,
)
