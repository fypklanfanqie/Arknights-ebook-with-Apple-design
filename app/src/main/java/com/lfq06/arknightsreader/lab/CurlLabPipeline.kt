package com.lfq06.arknightsreader.lab

import com.lfq06.arknightsreader.turn.CurlMesh
import com.lfq06.arknightsreader.turn.CurlSolver
import com.lfq06.arknightsreader.turn.CurlState
import com.lfq06.arknightsreader.turn.PageSide
import com.lfq06.arknightsreader.turn.TurnGesture
import com.lfq06.arknightsreader.turn.TurnGestureAction
import com.lfq06.arknightsreader.turn.TurnGestureEnv
import com.lfq06.arknightsreader.turn.TurnGestureState
import com.lfq06.arknightsreader.turn.TurnOutcome
import com.lfq06.arknightsreader.turn.TurnPhase
import com.lfq06.arknightsreader.turn.TurnRect
import com.lfq06.arknightsreader.turn.Vec2
import com.lfq06.arknightsreader.turn.VelocitySample
import com.lfq06.arknightsreader.turngl.CurlFrameParams

/**
 * Pure pipeline coordinator for the curl lab: turns raw touch events into
 * reducer actions, solver calls, mesh builds, and frame params. No Android
 * classes touch this object, so the whole drag pipeline is JVM-testable.
 *
 * Pipeline per move: clientToCanonical -> resolveDragDirection (press only) ->
 * reduce(Press/Move) -> constrained target -> CurlSolver.solve ->
 * CurlMesh.build (into a reused output) -> CurlFrameParams for the renderer.
 *
 * Drag-path invariants (mirrors the reader-integration contract):
 * - no re-pagination (page dims fixed at reset),
 * - no Bitmap/texture work (textures are pre-uploaded at idle),
 * - no database access (the reader's page source is consulted only on
 *   commit/settle, never during a drag).
 */
class CurlLabPipeline(
    private val env: TurnGestureEnv = TurnGestureEnv(canPrev = true, canNext = true),
) {
    /** Grid resolution of the curl mesh. */
    var cols = 24
    var rows = 16

    /** Requested fold radius as a fraction of page width. */
    var radiusFraction = 0.05

    var pageW = 420.0
        private set
    var pageH = 560.0
        private set

    /** Screen-space rect of the page inside the TextureView. */
    var pageRect: TurnRect = TurnRect(0.0, 0.0, 420.0, 560.0)

    private var gesture: TurnGestureState = TurnGesture.initialState()
    private var output: CurlMesh.MeshOutput = CurlMesh.allocOutput(cols, rows)

    /** Outcome of the last settle, consumed by the animation driver. */
    var pendingOutcome: TurnOutcome? = null
        private set

    val gestureState: TurnGestureState get() = gesture

    /** Current mesh result from the last [frameFor] call. */
    var lastMesh: CurlMesh.MeshResult? = null
        private set

    /** Curl state from the most recent solve, for diagnostics. */
    var lastCurl: CurlState? = null
        private set

    /** Called when the reducer's generation changes (new transaction armed). */
    var onGenerationChanged: ((Int) -> Unit)? = null

    /** Resizes the page; reallocates the mesh output only when the grid changes. */
    fun configure(pageW: Double, pageH: Double, rect: TurnRect, cols: Int = this.cols, rows: Int = this.rows) {
        this.pageW = pageW
        this.pageH = pageH
        this.pageRect = rect
        if (cols != this.cols || rows != this.rows || output.positions.size != CurlMesh.allocOutput(cols, rows).positions.size) {
            this.cols = cols
            this.rows = rows
            output = CurlMesh.allocOutput(cols, rows)
        }
        reset()
    }

    /** Drops any in-flight gesture and starts from IDLE. */
    fun reset() {
        val nextGeneration = gesture.generation + 1
        gesture = TurnGesture.reduce(gesture, TurnGestureAction.Reset)
        pendingOutcome = null
        lastCurl = null
        onGenerationChanged?.invoke(nextGeneration)
    }

    /** Touch down at client coordinates. Returns false if navigation is blocked. */
    fun press(pointerId: Long, x: Double, y: Double, timeMs: Double): Boolean {
        val canonical = TurnGesture.clientToCanonical(
            Vec2(x, y), pageRect, com.lfq06.arknightsreader.turn.PageDims(pageW, pageH),
            dir = 1, mode = com.lfq06.arknightsreader.turn.PageMode.SINGLE,
        )
        val dir = when (val resolved = TurnGesture.resolveDragDirection(canonical, canonical, env)) {
            is com.lfq06.arknightsreader.turn.DragDirection.Resolved -> resolved.dir
            else -> 1
        }
        val before = gesture.generation
        gesture = TurnGesture.reduce(
            gesture,
            TurnGestureAction.Press(pointerId, canonical, timeMs, dir),
            env,
        )
        return gesture.generation != before || gesture.phase != TurnPhase.IDLE
    }

    /**
     * Direction resolution for a moved pointer: forward (dir=1) grabs the free
     * edge; backward (dir=-1) mirrors the world so the same curl math applies.
     * Called once after the slop threshold resolves the swipe direction.
     */
    fun resolveDirection(x: Double, y: Double, startX: Double, startY: Double): Int {
        val start = TurnGesture.clientToCanonical(
            Vec2(startX, startY), pageRect, com.lfq06.arknightsreader.turn.PageDims(pageW, pageH),
            dir = 1, mode = com.lfq06.arknightsreader.turn.PageMode.SINGLE,
        )
        val current = TurnGesture.clientToCanonical(
            Vec2(x, y), pageRect, com.lfq06.arknightsreader.turn.PageDims(pageW, pageH),
            dir = 1, mode = com.lfq06.arknightsreader.turn.PageMode.SINGLE,
        )
        return when (val d = TurnGesture.resolveDragDirection(start, current, env, gesture.dir)) {
            is com.lfq06.arknightsreader.turn.DragDirection.Resolved -> d.dir
            is com.lfq06.arknightsreader.turn.DragDirection.Blocked -> gesture.dir
            com.lfq06.arknightsreader.turn.DragDirection.Pending -> gesture.dir
        }
    }

    /** Pointer moved; updates the drag target in canonical space. */
    fun move(pointerId: Long, x: Double, y: Double, timeMs: Double) {
        val canonical = TurnGesture.clientToCanonical(
            Vec2(x, y), pageRect, com.lfq06.arknightsreader.turn.PageDims(pageW, pageH),
            dir = gesture.dir.takeIf { it != 0 } ?: 1,
            mode = com.lfq06.arknightsreader.turn.PageMode.SINGLE,
        )
        gesture = TurnGesture.reduce(gesture, TurnGestureAction.Move(pointerId, canonical, timeMs))
        // Per-frame pipeline: constrained Q -> solve -> Progress into the
        // reducer, so decideRelease sees live progress instead of relying on
        // velocity alone.
        if (gesture.phase.isActive) {
            solveCurrent()?.let { state ->
                gesture = TurnGesture.reduce(
                    gesture,
                    TurnGestureAction.Progress(state.progress, timeMs),
                )
            }
        }
    }

    /** Grants the drag once the async arming token arrives (generation-checked). */
    fun arm(timeMs: Double): Boolean {
        val armed = TurnGesture.reduce(gesture, TurnGestureAction.Armed(gesture.generation, timeMs))
        if (armed === gesture) return false
        gesture = armed
        return true
    }

    /** Pointer released; resolves commit/cancel and stores the settle outcome. */
    fun release(pointerId: Long, x: Double, y: Double, timeMs: Double, velocityPxPerMs: Double): TurnOutcome? {
        val canonical = TurnGesture.clientToCanonical(
            Vec2(x, y), pageRect, com.lfq06.arknightsreader.turn.PageDims(pageW, pageH),
            dir = gesture.dir.takeIf { it != 0 } ?: 1,
            mode = com.lfq06.arknightsreader.turn.PageMode.SINGLE,
        )
        gesture = TurnGesture.reduce(
            gesture,
            TurnGestureAction.Release(pointerId, canonical, timeMs),
        )
        if (gesture.phase != TurnPhase.SETTLING) return null
        pendingOutcome = gesture.outcome
        return gesture.outcome
    }

    /** External interrupt (blur / lost capture / hide). */
    fun interrupt(timeMs: Double) {
        gesture = TurnGesture.reduce(gesture, TurnGestureAction.Cancel(timeMs))
    }

    /** Clears the settle outcome after the settle animation finishes. */
    fun clearOutcome() {
        pendingOutcome = null
        gesture = TurnGestureState(generation = gesture.generation)
    }

    /**
     * Builds the frame params for the current gesture target. Returns null
     * when the page should draw flat (no active drag and no settle animation).
     */
    fun frameFor(): CurlFrameParams? {
        val curl = if (gesture.phase.isActive) {
            solveCurrent()
        } else {
            lastCurl
        }
        return curl?.let { paramsFor(it) }
    }

    /** Solves the curl for the current gesture snapshot and caches the mesh. */
    fun solveCurrent(): CurlState? {
        if (!gesture.phase.isActive) return null
        val dir = gesture.dir.takeIf { it != 0 } ?: 1
        val grab = gesture.grab
        val target = gesture.target
        val state = CurlSolver.solve(
            grab = grab,
            target = target,
            pageWidth = pageW,
            pageHeight = pageH,
            requestedRadius = pageW * radiusFraction,
        )
        lastCurl = state
        lastMesh = CurlMesh.build(
            pageW = pageW,
            pageH = pageH,
            originX = 0.0,
            cols = cols,
            rows = rows,
            axisPoint = CurlMesh.canonicalToMeshPoint(state.axisPoint, dir, 0.0, pageW),
            axisNormal = CurlMesh.canonicalToMeshVector(state.axisNormal, dir),
            radius = state.radius,
            output = output,
        )
        return state
    }

    /** Builds frame params from a solved curl state. */
    fun paramsFor(state: CurlState): CurlFrameParams {
        val dir = gesture.dir.takeIf { it != 0 } ?: 1
        return CurlFrameParams(
            axisPoint = CurlMesh.canonicalToMeshPoint(state.axisPoint, dir, 0.0, pageW),
            axisNormal = CurlMesh.canonicalToMeshVector(state.axisNormal, dir),
            radius = state.radius,
            pageW = pageW,
            pageH = pageH,
            halfThickness = 0.35,
            pageVisible = true,
        )
    }

    /** Diagnostics line for the on-screen status overlay. */
    fun statusLine(): String {
        val s = gesture
        val curl = lastCurl
        return buildString {
            append("phase=").append(s.phase)
            append(" gen=").append(s.generation)
            append(" dir=").append(s.dir)
            append(" progress=").append(if (curl == null) "0.00" else "%.2f".format(curl.progress))
            append(" r=").append(if (curl == null) "0.0" else "%.1f".format(curl.radius))
            if (curl != null) {
                append(" ax=(")
                append("%.0f".format(curl.axisPoint.x)); append(",")
                append("%.0f".format(curl.axisPoint.y)); append(")")
                append(" n=(")
                append("%.2f".format(curl.axisNormal.x)); append(",")
                append("%.2f".format(curl.axisNormal.y)); append(")")
                append(" ").append(curl.phase)
            }
        }
    }

    private fun pageScale(): Double = if (pageRect.width > 1e-9) pageW / pageRect.width else 1.0

    private val TurnPhase.isActive: Boolean
        get() = this == TurnPhase.PRESSING || this == TurnPhase.ARMING || this == TurnPhase.DRAGGING
}
