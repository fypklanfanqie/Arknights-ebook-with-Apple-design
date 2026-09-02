package com.lfq06.arknightsreader.model

enum class MotionPreference {
    SYSTEM,
    REDUCED,
    OFF,
}

data class Persona(
    val themeId: String,
    val fontIndex: Int,
    val fontSize: Int,
    val lineHeight: Float,
    val motion: MotionPreference = MotionPreference.SYSTEM,
)