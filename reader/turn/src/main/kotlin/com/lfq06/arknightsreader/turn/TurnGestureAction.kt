package com.lfq06.arknightsreader.turn

/** All gesture actions carry their own coordinates and timestamps. */
sealed interface TurnGestureAction {
    data object Reset : TurnGestureAction

    data class Press(
        val pointerId: Long,
        val point: Vec2,
        val time: Double,
        val dir: Int,
    ) : TurnGestureAction

    data class Move(
        val pointerId: Long,
        val point: Vec2,
        val time: Double,
    ) : TurnGestureAction

    /** Async token granting the drag; stale generations are rejected. */
    data class Armed(
        val generation: Int,
        val time: Double,
    ) : TurnGestureAction

    data class Progress(
        val value: Double,
        val time: Double,
    ) : TurnGestureAction

    data class Release(
        val pointerId: Long,
        val point: Vec2,
        val time: Double,
    ) : TurnGestureAction

    data class Cancel(val time: Double) : TurnGestureAction

    data class LostCapture(val time: Double) : TurnGestureAction

    data class Blur(val time: Double) : TurnGestureAction

    data class Hidden(val time: Double) : TurnGestureAction
}
