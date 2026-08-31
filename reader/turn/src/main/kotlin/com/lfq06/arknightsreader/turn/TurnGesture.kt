package com.lfq06.arknightsreader.turn

import kotlin.math.abs

/**
 * Pure cancellable page-turn gesture state machine. No DOM, no renderer, no
 * clock: coordinates and timestamps arrive on [TurnGestureAction]s, so every
 * transition is deterministic and unit-testable.
 *
 * Canonical space: hinge at x=0, free edge at x=W, y centered on the page
 * middle (top negative). Committing a turn drags the grab toward x=0.
 */
object TurnGesture {
    const val SLOP = 5.0
    const val COMMIT_PROGRESS = 0.5
    const val VELOCITY_THRESHOLD = 0.45
    const val VELOCITY_FRESH_MS = 120.0

    private const val EPS = 1e-9

    private fun Double.finiteNumber(fallback: Double): Double =
        if (isFinite()) this else fallback

    fun initialState(): TurnGestureState = TurnGestureState()

    fun reduce(
        state: TurnGestureState,
        action: TurnGestureAction,
        env: TurnGestureEnv = TurnGestureEnv(),
    ): TurnGestureState = when (action) {
        TurnGestureAction.Reset -> TurnGestureState(generation = state.generation + 1)

        is TurnGestureAction.Press -> onPress(state, action, env)
        is TurnGestureAction.Move -> onMove(state, action)
        is TurnGestureAction.Armed -> onArmed(state, action)
        is TurnGestureAction.Progress -> onProgress(state, action)
        is TurnGestureAction.Release -> onRelease(state, action)
        is TurnGestureAction.Cancel -> onInterrupt(state)
        is TurnGestureAction.LostCapture -> onInterrupt(state)
        is TurnGestureAction.Blur -> onInterrupt(state)
        is TurnGestureAction.Hidden -> onInterrupt(state)
    }

    private fun onPress(
        state: TurnGestureState,
        action: TurnGestureAction.Press,
        env: TurnGestureEnv,
    ): TurnGestureState {
        if (state.phase != TurnPhase.IDLE) return state
        val rawDir = action.dir.toDouble().finiteNumber(0.0)
        if (rawDir == 0.0) return state
        val dir = if (rawDir >= 1.0) 1 else -1
        if (dir >= 1 && !env.canNext) return state
        if (dir <= -1 && !env.canPrev) return state
        val grab = sanitizePoint(action.point)
        return TurnGestureState(
            phase = TurnPhase.PRESSING,
            pointerId = action.pointerId,
            dir = dir,
            grab = grab,
            target = grab,
            progress = 0.0,
            velocity = null,
            lastMove = TurnMoveSample(grab, action.time.finiteNumber(0.0)),
            generation = state.generation,
            outcome = null,
        )
    }

    private fun onMove(
        state: TurnGestureState,
        action: TurnGestureAction.Move,
    ): TurnGestureState {
        // A pointer that did not start the transaction cannot move it.
        if (state.pointerId == null || action.pointerId != state.pointerId) return state
        if (!state.phase.isActive) return state
        val point = sanitizePoint(action.point, fallback = state.grab)
        val moveTime = action.time.finiteNumber(state.lastMove?.time ?: 0.0)
        val next = state.copy(
            lastMove = TurnMoveSample(point, moveTime),
            velocity = commitVelocity(state.lastMove, point, moveTime) ?: state.velocity,
        )
        return when (state.phase) {
            TurnPhase.PRESSING ->
                if (distance(point, state.grab) > SLOP) next.copy(phase = TurnPhase.ARMING) else next
            TurnPhase.DRAGGING -> next.copy(target = point)
            else -> next // arming holds until the async armed token arrives
        }
    }

    private fun onArmed(
        state: TurnGestureState,
        action: TurnGestureAction.Armed,
    ): TurnGestureState {
        if (state.phase != TurnPhase.ARMING) return state
        if (action.generation != state.generation) return state
        // Snap the target to wherever the finger already is, so the curl does not
        // spend a frame flat at the grab point when the pointer moved during arming.
        val snappedTarget = state.lastMove?.point ?: state.target
        return state.copy(phase = TurnPhase.DRAGGING, target = snappedTarget)
    }

    private fun onProgress(
        state: TurnGestureState,
        action: TurnGestureAction.Progress,
    ): TurnGestureState {
        if (!state.phase.isActive) return state
        return state.copy(progress = action.value.finiteNumber(0.0).coerceIn(0.0, 1.0))
    }

    private fun onRelease(
        state: TurnGestureState,
        action: TurnGestureAction.Release,
    ): TurnGestureState {
        if (state.pointerId == null || action.pointerId != state.pointerId) return state
        if (!state.phase.isActive) return state
        val outcome = if (state.phase == TurnPhase.DRAGGING) {
            decideOutcome(state, action.time.finiteNumber(0.0))
        } else {
            TurnOutcome.Cancel
        }
        return state.copy(phase = TurnPhase.SETTLING, outcome = outcome)
    }

    private fun onInterrupt(state: TurnGestureState): TurnGestureState {
        if (!state.phase.isActive) return state
        return state.copy(phase = TurnPhase.SETTLING, outcome = TurnOutcome.Cancel)
    }

    private fun commitVelocity(
        previous: TurnMoveSample?,
        nextPoint: Vec2,
        nextTime: Double,
    ): TurnVelocitySample? {
        previous ?: return null
        val dt = nextTime - previous.time
        if (dt <= EPS) return null
        // Canonical space always has the hinge at x=0, so committing a turn drags
        // the grab toward x=0; -dx is therefore commitward.
        val value = -(nextPoint.x - previous.point.x) / dt
        if (!value.isFinite()) return null
        return TurnVelocitySample(value, nextTime)
    }

    private fun freshCommitVelocity(state: TurnGestureState, now: Double): Double {
        val sample = state.velocity ?: return 0.0
        val age = now - sample.time
        if (!age.isFinite() || age < 0.0 || age > VELOCITY_FRESH_MS) return 0.0
        val value = sample.value.finiteNumber(0.0)
        // Only a commitward sample can commit; a backward flick falls through to
        // the progress rule instead of forcing a cancel.
        return if (value > 0.0) value else 0.0
    }

    private fun decideOutcome(state: TurnGestureState, now: Double): TurnOutcome {
        val progress = state.progress.finiteNumber(0.0).coerceIn(0.0, 1.0)
        val velocity = freshCommitVelocity(state, now)
        return if (progress >= COMMIT_PROGRESS || velocity > VELOCITY_THRESHOLD) {
            TurnOutcome.Commit
        } else {
            TurnOutcome.Cancel
        }
    }

    private val TurnPhase.isActive: Boolean
        get() = this == TurnPhase.PRESSING || this == TurnPhase.ARMING || this == TurnPhase.DRAGGING

    private fun sanitizePoint(point: Vec2, fallback: Vec2 = Vec2(0.0, 0.0)): Vec2 = Vec2(
        point.x.finiteNumber(fallback.x),
        point.y.finiteNumber(fallback.y),
    )

    private fun distance(a: Vec2, b: Vec2): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val squared = dx * dx + dy * dy
        if (!squared.isFinite()) return Double.POSITIVE_INFINITY
        return kotlin.math.sqrt(squared)
    }

    /**
     * Resolves navigation from horizontal displacement only after a strict
     * [SLOP], preserving a transaction's locked direction and blocking
     * unavailable turns. Vertical motion never resolves.
     */
    fun resolveDragDirection(
        start: Vec2,
        current: Vec2,
        env: TurnGestureEnv,
        lockedDir: Int = 0,
    ): DragDirection {
        val locked = lockedDir.toDouble().finiteNumber(0.0)
        if (locked != 0.0) return DragDirection.Resolved(if (locked > 0.0) 1 else -1)
        val origin = sanitizePoint(start)
        val point = sanitizePoint(current, origin)
        val dx = point.x - origin.x
        if (abs(dx) <= SLOP) return DragDirection.Pending
        val dir = if (dx > 0.0) 1 else -1
        val available = if (dir > 0) env.canNext else env.canPrev
        return if (available) DragDirection.Resolved(dir) else DragDirection.Blocked(dir)
    }

    /**
     * Maps a screen/client point into canonical hinge=0 / free-edge=pageW space.
     * Mirroring the backward side onto the forward side keeps left and right
     * turns symmetric, which prevents backward-grab mirroring bugs.
     */
    fun clientToCanonical(
        client: Vec2,
        rect: TurnRect,
        dims: PageDims,
        dir: Int,
        mode: PageMode,
    ): Vec2 {
        val pageW = maxOf(0.0, dims.pageW.finiteNumber(0.0))
        val pageH = maxOf(0.0, dims.pageH.finiteNumber(0.0))
        val left = rect.left.finiteNumber(0.0)
        val top = rect.top.finiteNumber(0.0)
        val width = maxOf(0.0, rect.width.finiteNumber(0.0))
        val height = maxOf(0.0, rect.height.finiteNumber(0.0))
        val cx = client.x.finiteNumber(0.0)
        val cy = client.y.finiteNumber(0.0)

        val regionW = if (mode == PageMode.DOUBLE) width / 2.0 else width
        val scale = if (regionW > EPS) pageW / regionW else 1.0
        val forward = dir.toDouble().finiteNumber(0.0) >= 1.0
        val regionLeft = if (mode == PageMode.DOUBLE && forward) left + width / 2.0 else left
        val mirror = !forward
        val localX = cx - regionLeft
        val canX = (if (mirror) regionW - localX else localX) * scale
        val canY = if (height > EPS) (cy - (top + height / 2.0)) * (pageH / height) else 0.0
        return Vec2(canX, canY)
    }

    /** CSS fallback rotation whose signs track the physical page hinges. */
    fun fallbackRotateY(dir: Int, progress: Double): Double {
        val clamped = progress.finiteNumber(0.0).coerceIn(0.0, 1.0)
        return clamped * 180.0 * if (dir.toDouble().finiteNumber(0.0) > 0.0) -1.0 else 1.0
    }
}
