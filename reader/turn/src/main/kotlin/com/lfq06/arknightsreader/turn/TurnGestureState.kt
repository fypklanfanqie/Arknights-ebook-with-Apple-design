package com.lfq06.arknightsreader.turn

/** Phase of one page-turn gesture transaction. */
enum class TurnPhase { IDLE, PRESSING, ARMING, DRAGGING, SETTLING }

/** Decided fate of a settled transaction. */
sealed interface TurnOutcome {
    data object Commit : TurnOutcome

    data object Cancel : TurnOutcome
}

/** A timestamped pointer move; [time] is caller-supplied milliseconds. */
data class TurnMoveSample(
    val point: Vec2,
    val time: Double,
)

/**
 * A timestamped commitward velocity reading. A positive [value] drags toward
 * the hinge (commitward); the sign is already resolved in canonical space.
 */
data class TurnVelocitySample(
    val value: Double,
    val time: Double,
)

/** Navigation availability supplied by the caller at reduce time. */
data class TurnGestureEnv(
    val canPrev: Boolean = true,
    val canNext: Boolean = true,
)

/** Immutable snapshot of the gesture state machine. */
data class TurnGestureState(
    val phase: TurnPhase = TurnPhase.IDLE,
    val pointerId: Long? = null,
    val dir: Int = 0,
    val grab: Vec2 = Vec2(0.0, 0.0),
    val target: Vec2 = Vec2(0.0, 0.0),
    val progress: Double = 0.0,
    val velocity: TurnVelocitySample? = null,
    val lastMove: TurnMoveSample? = null,
    val generation: Int = 0,
    val outcome: TurnOutcome? = null,
)
