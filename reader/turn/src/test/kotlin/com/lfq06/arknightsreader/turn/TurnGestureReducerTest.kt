package com.lfq06.arknightsreader.turn

import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavior contract for the pure gesture reducer, ported from the web reader's
 * gesture-state test suite. Timestamps and points ride on actions; the reducer
 * itself never reads a clock.
 */
class TurnGestureReducerTest {
    private val env = TurnGestureEnv(canPrev = true, canNext = true)

    /** Canonical free-edge grab point for a forward press (hinge x=0, free x=W). */
    private val g0 = Vec2(420.0, -30.0)

    private fun press(
        pointerId: Long = 1L,
        point: Vec2 = g0,
        time: Double = 0.0,
        dir: Int = 1,
        env: TurnGestureEnv = this.env,
    ): TurnGestureState = TurnGesture.reduce(
        TurnGesture.initialState(),
        TurnGestureAction.Press(pointerId, point, time, dir),
        env,
    )

    private fun move(
        state: TurnGestureState,
        point: Vec2,
        time: Double,
        pointerId: Long = 1L,
    ): TurnGestureState = TurnGesture.reduce(
        state,
        TurnGestureAction.Move(pointerId, point, time),
        env,
    )

    private fun armed(state: TurnGestureState, time: Double = 11.0): TurnGestureState =
        TurnGesture.reduce(state, TurnGestureAction.Armed(state.generation, time), env)

    // ---- lifecycle ----

    @Test
    fun `initial state is idle with zero generation`() {
        val state = TurnGesture.initialState()
        assertEquals(TurnPhase.IDLE, state.phase)
        assertEquals(0, state.generation)
        assertEquals(null, state.outcome)
    }

    @Test
    fun `press enters pressing with grab target and last move at the point`() {
        val state = press()
        assertEquals(TurnPhase.PRESSING, state.phase)
        assertEquals(1L, state.pointerId)
        assertEquals(1, state.dir)
        assertEquals(g0, state.grab)
        assertEquals(g0, state.target)
        assertEquals(0.0, state.progress)
        assertEquals(TurnMoveSample(g0, 0.0), state.lastMove)
    }

    @Test
    fun `press is rejected while not idle`() {
        val before = press()
        val after = TurnGesture.reduce(
            before,
            TurnGestureAction.Press(2L, Vec2(10.0, 10.0), 5.0, 1),
            env,
        )
        assertSame(before, after)
    }

    @Test
    fun `press is rejected for zero direction or unavailable navigation`() {
        assertEquals(
            TurnGesture.initialState(),
            TurnGesture.reduce(TurnGesture.initialState(), TurnGestureAction.Press(1L, g0, 0.0, 0), env),
        )
        assertEquals(
            TurnGesture.initialState(),
            TurnGesture.reduce(
                TurnGesture.initialState(),
                TurnGestureAction.Press(1L, g0, 0.0, 1),
                TurnGestureEnv(canPrev = true, canNext = false),
            ),
        )
        assertEquals(
            TurnGesture.initialState(),
            TurnGesture.reduce(
                TurnGesture.initialState(),
                TurnGestureAction.Press(1L, g0, 0.0, -1),
                TurnGestureEnv(canPrev = false, canNext = true),
            ),
        )
    }

    @Test
    fun `reset returns to idle and increments generation`() {
        val before = press()
        val after = TurnGesture.reduce(before, TurnGestureAction.Reset, env)
        assertEquals(TurnPhase.IDLE, after.phase)
        assertEquals(before.generation + 1, after.generation)
    }

    // ---- slop and phase transitions ----

    @Test
    fun `movement stays pressing until slop is crossed then enters arming`() {
        var state = press(point = Vec2(420.0, -180.0))
        state = move(state, Vec2(417.0, -178.0), 10.0)
        assertEquals(TurnPhase.PRESSING, state.phase)
        state = move(state, Vec2(414.0, -178.0), 20.0)
        assertEquals(TurnPhase.ARMING, state.phase)
    }

    @Test
    fun `armed only transitions from arming and snaps target to last move`() {
        var state = press()
        state = move(state, Vec2(380.0, -120.0), 10.0)
        assertEquals(TurnPhase.ARMING, state.phase)
        state = armed(state)
        assertEquals(TurnPhase.DRAGGING, state.phase)
        assertEquals(Vec2(380.0, -120.0), state.target)
    }

    @Test
    fun `armed is rejected while still pressing`() {
        val before = press()
        val after = TurnGesture.reduce(before, TurnGestureAction.Armed(before.generation, 5.0), env)
        assertSame(before, after)
        assertEquals(TurnPhase.PRESSING, after.phase)
    }

    @Test
    fun `stale generation armed token cannot arm a newer transaction`() {
        var state = press()
        state = move(state, Vec2(380.0, -30.0), 10.0)
        assertEquals(TurnPhase.ARMING, state.phase)
        val oldGeneration = state.generation
        state = TurnGesture.reduce(state, TurnGestureAction.Reset, env)
        state = TurnGesture.reduce(state, TurnGestureAction.Press(1L, g0, 20.0, 1), env)
        state = move(state, Vec2(380.0, -30.0), 30.0)
        assertEquals(TurnPhase.ARMING, state.phase)

        val before = state
        val stale = TurnGesture.reduce(state, TurnGestureAction.Armed(oldGeneration, 31.0), env)
        assertSame(before, stale)
        assertEquals(TurnPhase.ARMING, stale.phase)

        val fresh = TurnGesture.reduce(state, TurnGestureAction.Armed(state.generation, 32.0), env)
        assertEquals(TurnPhase.DRAGGING, fresh.phase)
    }

    // ---- pointer identity ----

    @Test
    fun `a second pointer cannot move or release the active transaction`() {
        val before = press(pointerId = 7L)
        val afterMove = move(before, Vec2(0.0, 0.0), 10.0, pointerId = 8L)
        assertSame(before, afterMove)
        val afterRelease = TurnGesture.reduce(
            before,
            TurnGestureAction.Release(8L, Vec2(0.0, 0.0), 20.0),
            env,
        )
        assertSame(before, afterRelease)
    }

    // ---- dragging ----

    @Test
    fun `active pointer movement updates both target coordinates while dragging`() {
        var state = press()
        state = move(state, Vec2(380.0, -120.0), 10.0)
        state = armed(state)
        state = move(state, Vec2(300.0, 35.0), 20.0)
        assertEquals(TurnPhase.DRAGGING, state.phase)
        assertEquals(Vec2(300.0, 35.0), state.target)
    }

    @Test
    fun `progress clamps into unit range while a phase is active`() {
        var state = press()
        state = move(state, Vec2(380.0, -30.0), 10.0)
        state = armed(state)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.3, 20.0), env)
        assertEquals(0.3, state.progress)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(7.0, 21.0), env)
        assertEquals(1.0, state.progress)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(-3.0, 22.0), env)
        assertEquals(0.0, state.progress)
    }

    @Test
    fun `nonfinite coordinates on move are sanitized against the grab`() {
        var state = press()
        state = move(state, Vec2(Double.NaN, -100.0), 10.0)
        assertEquals(Vec2(420.0, -100.0), state.lastMove?.point)
    }

    // ---- release decisions ----

    @Test
    fun `fresh velocity commits a release below half progress`() {
        // progress stays below COMMIT_PROGRESS (0.3 < 0.5); the commit comes from
        // the velocity branch: the last drag moved 140 canonical px commitward in
        // 30ms (velocity ~4.67 > VELOCITY_THRESHOLD) and is fresh at release
        // (age 5ms < VELOCITY_FRESH_MS).
        var state = press()
        state = move(state, Vec2(360.0, -180.0), 10.0)
        state = armed(state)
        state = move(state, Vec2(220.0, -180.0), 40.0)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.3, 40.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(220.0, -180.0), 45.0), env)
        assertEquals(TurnPhase.SETTLING, state.phase)
        assertEquals(TurnOutcome.Commit, state.outcome)
    }

    @Test
    fun `progress at or past half commits even with stale velocity`() {
        // Pure progress path: progress >= COMMIT_PROGRESS commits regardless of
        // velocity. The only velocity sample is stale by release time (age =
        // 390ms > VELOCITY_FRESH_MS), so the velocity branch alone yields 0.
        var state = press()
        state = move(state, Vec2(300.0, -180.0), 10.0)
        state = armed(state)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.5, 40.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(300.0, -180.0), 400.0), env)
        assertEquals(TurnPhase.SETTLING, state.phase)
        assertEquals(TurnOutcome.Commit, state.outcome)
    }

    @Test
    fun `release below half with stale velocity cancels`() {
        var state = press()
        state = move(state, Vec2(300.0, -180.0), 10.0)
        state = armed(state)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.3, 20.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(300.0, -180.0), 250.0), env)
        assertEquals(TurnOutcome.Cancel, state.outcome)
    }

    @Test
    fun `a fresh commitward flick commits via velocity without crossing half`() {
        var state = press()
        state = move(state, Vec2(380.0, -30.0), 10.0)
        state = armed(state)
        state = move(state, Vec2(360.0, -30.0), 20.0)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.2, 20.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(360.0, -30.0), 20.0), env)
        assertEquals(TurnOutcome.Commit, state.outcome)
    }

    @Test
    fun `release during pressing or arming always cancels`() {
        var state = press()
        state = move(state, Vec2(360.0, -30.0), 10.0)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(360.0, -30.0), 20.0), env)
        assertEquals(TurnOutcome.Cancel, state.outcome)
    }

    // ---- cancellations ----

    @Test
    fun `cancel lost capture blur and hidden always settle to cancel`() {
        for (action in listOf(
            TurnGestureAction.Cancel(20.0),
            TurnGestureAction.LostCapture(20.0),
            TurnGestureAction.Blur(20.0),
            TurnGestureAction.Hidden(20.0),
        )) {
            var state = press()
            state = move(state, Vec2(300.0, -100.0), 10.0)
            state = armed(state)
            state = TurnGesture.reduce(state, action, env)
            assertEquals(TurnPhase.SETTLING, state.phase, "action $action")
            assertEquals(TurnOutcome.Cancel, state.outcome, "action $action")
        }
    }

    @Test
    fun `cancellation during idle is a no-op`() {
        val before = TurnGesture.initialState()
        val after = TurnGesture.reduce(before, TurnGestureAction.Cancel(5.0), env)
        assertSame(before, after)
    }

    // ---- direction resolution ----

    @Test
    fun `rightward displacement resolves next after horizontal slop`() {
        assertEquals(
            DragDirection.Resolved(1),
            TurnGesture.resolveDragDirection(
                Vec2(100.0, 100.0), Vec2(106.0, 180.0), env, lockedDir = 0,
            ),
        )
    }

    @Test
    fun `leftward displacement resolves previous after horizontal slop`() {
        assertEquals(
            DragDirection.Resolved(-1),
            TurnGesture.resolveDragDirection(
                Vec2(100.0, 100.0), Vec2(94.0, 20.0), env, lockedDir = 0,
            ),
        )
    }

    @Test
    fun `vertical movement cannot resolve a drag direction`() {
        assertEquals(
            DragDirection.Pending,
            TurnGesture.resolveDragDirection(
                Vec2(100.0, 100.0), Vec2(105.0, 220.0), env, lockedDir = 0,
            ),
        )
    }

    @Test
    fun `unavailable direction is blocked rather than inverted`() {
        assertEquals(
            DragDirection.Blocked(1),
            TurnGesture.resolveDragDirection(
                Vec2(100.0, 100.0), Vec2(106.0, 100.0),
                TurnGestureEnv(canPrev = true, canNext = false), lockedDir = 0,
            ),
        )
    }

    @Test
    fun `locked direction cannot change after pointer reversal`() {
        assertEquals(
            DragDirection.Resolved(1),
            TurnGesture.resolveDragDirection(
                Vec2(100.0, 100.0), Vec2(50.0, 100.0), env, lockedDir = 1,
            ),
        )
    }

    // ---- canonical mapping ----

    @Test
    fun `single and double canonical mapping is mirror symmetric`() {
        val dims = PageDims(pageW = 420.0, pageH = 560.0)
        val right = TurnGesture.clientToCanonical(
            Vec2(790.0, 250.0), TurnRect(0.0, 0.0, 840.0, 560.0), dims, dir = 1, mode = PageMode.DOUBLE,
        )
        val left = TurnGesture.clientToCanonical(
            Vec2(50.0, 250.0), TurnRect(0.0, 0.0, 840.0, 560.0), dims, dir = -1, mode = PageMode.DOUBLE,
        )
        assertTrue(kotlin.math.abs(right.x - left.x) < 1e-6)
        assertTrue(kotlin.math.abs(right.y - left.y) < 1e-6)

        val singleForward = TurnGesture.clientToCanonical(
            Vec2(370.0, 250.0), TurnRect(0.0, 0.0, 420.0, 560.0), dims, dir = 1, mode = PageMode.SINGLE,
        )
        val singleBackward = TurnGesture.clientToCanonical(
            Vec2(50.0, 250.0), TurnRect(0.0, 0.0, 420.0, 560.0), dims, dir = -1, mode = PageMode.SINGLE,
        )
        assertTrue(kotlin.math.abs(singleForward.x - singleBackward.x) < 1e-6)
        assertTrue(kotlin.math.abs(singleForward.y - singleBackward.y) < 1e-6)
    }

    @Test
    fun `canonical y is centered on the page middle`() {
        val dims = PageDims(pageW = 420.0, pageH = 560.0)
        val top = TurnGesture.clientToCanonical(
            Vec2(200.0, 0.0), TurnRect(0.0, 0.0, 420.0, 560.0), dims, dir = 1, mode = PageMode.SINGLE,
        )
        assertEquals(-280.0, top.y)
        val middle = TurnGesture.clientToCanonical(
            Vec2(200.0, 280.0), TurnRect(0.0, 0.0, 420.0, 560.0), dims, dir = 1, mode = PageMode.SINGLE,
        )
        assertEquals(0.0, middle.y)
    }

    // ---- full dispatch sequences ----

    @Test
    fun `full drag commit sequence reaches settling with commit`() {
        var state = press()
        assertEquals(TurnPhase.PRESSING, state.phase)
        state = move(state, Vec2(380.0, -30.0), 10.0)
        assertEquals(TurnPhase.ARMING, state.phase)
        state = armed(state)
        assertEquals(TurnPhase.DRAGGING, state.phase)
        state = move(state, Vec2(120.0, -30.0), 40.0)
        assertEquals(Vec2(120.0, -30.0), state.target)
        state = move(state, Vec2(100.0, -30.0), 50.0)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.72, 50.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(100.0, -30.0), 50.0), env)
        assertEquals(TurnPhase.SETTLING, state.phase)
        assertEquals(TurnOutcome.Commit, state.outcome)
    }

    @Test
    fun `full drag cancel sequence with stale velocity reaches cancel`() {
        var state = press()
        state = move(state, Vec2(400.0, -30.0), 10.0)
        state = armed(state)
        state = move(state, Vec2(395.0, -30.0), 40.0)
        state = TurnGesture.reduce(state, TurnGestureAction.Progress(0.12, 40.0), env)
        state = TurnGesture.reduce(state, TurnGestureAction.Release(1L, Vec2(395.0, -30.0), 300.0), env)
        assertEquals(TurnOutcome.Cancel, state.outcome)
    }

    // ---- CSS fallback rotation ----

    @Test
    fun `fallback rotate keeps physical hinge signs`() {
        assertEquals(-90.0, TurnGesture.fallbackRotateY(1, 0.5))
        assertEquals(90.0, TurnGesture.fallbackRotateY(-1, 0.5))
    }
}
