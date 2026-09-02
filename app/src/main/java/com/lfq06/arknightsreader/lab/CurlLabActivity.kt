package com.lfq06.arknightsreader.lab

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.lfq06.arknightsreader.turn.PageDims
import com.lfq06.arknightsreader.turn.TurnOutcome
import com.lfq06.arknightsreader.turn.TurnPhase
import com.lfq06.arknightsreader.turn.TurnRect
import com.lfq06.arknightsreader.turn.Vec2
import com.lfq06.arknightsreader.turngl.CurlEglFrame
import com.lfq06.arknightsreader.turngl.CurlFrameParams
import com.lfq06.arknightsreader.turngl.CurlTextureView

/**
 * "Page Curl Lab": a full-screen diagnostic surface for the turn engine.
 *
 * Layout: one [CurlTextureView] fills the screen; a status bar at the bottom
 * prints gesture phase / curl progress / radius / axis each frame.
 *
 * Thread contract (I-4):
 * - The UI thread routes touch events into the pure reducer
 *   ([CurlLabPipeline]) and pushes gesture progress; it never touches the
 *   mesh output or GL state.
 * - The render thread (host frame listener -> [prepareFrame]) does solve +
 *   CurlMesh.build + VBO upload, then RETURNS the params for this tick (C-3),
 *   so what is drawn is always the freshly computed snapshot.
 *
 * Touch pipeline (per event): clientToCanonical -> resolveDragDirection ->
 * Press/Move/Armed/Release into the pure reducer. Textures are pre-generated
 * at idle and uploaded once after EGL is ready, so a drag performs zero
 * Bitmap work and zero full texture uploads.
 */
class CurlLabActivity : Activity() {

    private lateinit var textureView: CurlTextureView
    private lateinit var statusView: TextView
    private lateinit var pipeline: CurlLabPipeline

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var directionResolved = false
    private var armed = false
    private var pointerId = -1

    // Settle animation state. Written on the UI thread (release path), read on
    // the render thread (prepareFrame); the host's dirty flag keeps the loop
    // spinning while the animation is in flight.
    @Volatile
    private var settleFrom: CurlFrameParams? = null

    @Volatile
    private var settleTo: CurlFrameParams? = null

    @Volatile
    private var settleIsCommit = false

    // Volatile so the render thread cannot observe a stale start time and jump
    // the settle animation to t=1 on its first frame.
    @Volatile
    private var settleStartMs = 0L

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null

    /** True once textures reached GL after EGL was ready (I-1 semantics). */
    @Volatile
    private var texturesUploaded = false

    private val slopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        textureView = CurlTextureView(this)
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        statusView = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 48)
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        pipeline = CurlLabPipeline()
        textureView.host.frameListener = com.lfq06.arknightsreader.turngl.CurlTextureViewHost.FrameListener {
            prepareFrame()
        }
        textureView.host.onEglReady = { uploadTextures() }

        // Page occupies the central area with margins, leaving room for the
        // status bar (the reader integration will use real page geometry).
        textureView.post { configurePipeline() }
    }

    private fun configurePipeline() {
        val vw = textureView.width.toFloat().coerceAtLeast(1f)
        val vh = textureView.height.toFloat().coerceAtLeast(1f)
        val marginX = vw * 0.10f
        val marginY = vh * 0.12f
        val pageWpx = (vw - marginX * 2).toDouble()
        val pageHpx = (vh - marginY * 2).toDouble()
        pipeline.configure(
            pageW = pageWpx,
            pageH = pageHpx,
            rect = TurnRect(marginX.toDouble(), marginY.toDouble(), pageWpx, pageHpx),
        )
        buildCheckerTextures(pageWpx.toInt().coerceAtLeast(64), pageHpx.toInt().coerceAtLeast(64))
        // Flat idle frame: no curl, page fully visible.
        textureView.setFrameParams(idleParams())
        statusView.post { updateStatus() }
    }

    private fun idleParams(): CurlFrameParams = CurlFrameParams(
        axisPoint = Vec2(pipeline.pageW / 2.0, 0.0),
        axisNormal = Vec2(1.0, 0.0),
        radius = 0.0,
        pageW = pipeline.pageW,
        pageH = pipeline.pageH,
        halfThickness = 0.35,
        pageVisible = true,
    )

    /**
     * Builds the two page-face textures once. The checker IS the curling
     * page's front/back content; drags never regenerate or re-upload them.
     */
    private fun buildCheckerTextures(w: Int, h: Int) {
        if (frontBitmap != null && backBitmap != null && frontBitmap!!.width == w) return
        val layout = CheckerPageTextures.layout(w, h)
        frontBitmap?.recycle()
        backBitmap?.recycle()
        frontBitmap = CheckerPageTextures.create(
            layout,
            caption = "FRONT 1",
            baseA = 0xFFF2E8D5.toInt(),
            baseB = 0xFFD8C8A8.toInt(),
        )
        backBitmap = CheckerPageTextures.create(
            layout,
            caption = "BACK 2",
            baseA = 0xFFD5E3F2.toInt(),
            baseB = 0xFFA8C2D8.toInt(),
        )
        texturesUploaded = false
        // I-1: no immediate GL upload from the UI thread. If EGL is already
        // ready, the host queues a frame whose prepare path uploads; otherwise
        // onEglReady fires later and uploads then.
        if (textureView.isRendererRunning()) {
            textureView.requestFrame()
        }
    }

    /**
     * Uploads textures to GL once the renderer is ready. Called from the
     * render thread only (onEglReady or the first prepared frame); safe to
     * call twice (I-1: the UI-thread immediate upload path was removed).
     */
    private fun uploadTextures() {
        val fb = frontBitmap ?: return
        val bb = backBitmap ?: return
        if (texturesUploaded) return
        CurlEglFrame.uploadTexture(fb, bb)
        texturesUploaded = true
    }

    /**
     * Runs on the render thread. Solves + builds the mesh + uploads it, then
     * RETURNS the params to draw this tick (C-3: the returned snapshot is what
     * reaches the GPU, never a stale cached field).
     */
    private fun prepareFrame(): CurlFrameParams? {
        uploadTextures()
        // Drag in flight: solve + mesh build + upload on the render thread.
        if (pipeline.gestureState.phase == TurnPhase.DRAGGING ||
            pipeline.gestureState.phase == TurnPhase.PRESSING ||
            pipeline.gestureState.phase == TurnPhase.ARMING
        ) {
            val drag = pipeline.frameFor()
            if (drag != null) {
                pipeline.lastMesh?.let { mesh -> textureView.host.uploadMesh(mesh) }
                return drag
            }
        }
        // Settle animation in flight.
        val to = settleTo
        val from = settleFrom
        if (to != null && from != null) {
            val startMs = settleStartMs
            val t = ((System.currentTimeMillis() - startMs) / SETTLE_MS).coerceIn(0.0, 1.0)
            val e = easeInOut(t)
            val lerped = lerpParams(from, to, e)
            if (t >= 1.0) {
                val wasCommit = settleIsCommit
                settleFrom = null
                settleTo = null
                settleIsCommit = false
                pipeline.clearOutcome()
                // The settle finished: stop the continuous dirty loop NOW, in
                // the same code path that consumed it. Without this the render
                // thread spins at full speed redrawing the idle frame forever.
                textureView.setDirty(false)
                if (wasCommit) {
                    // Reset the page to flat for the next turn (real page
                    // swap is the next task's job).
                    return idleParams()
                }
            }
            return lerped
        }
        // Flat idle: draw the page at its idle pose.
        return idleParams()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                directionResolved = false
                armed = false
                cancelSettle()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()?.apply { addMovement(event) }
                pipeline.press(pointerId.toLong(), event.x.toDouble(), event.y.toDouble(), event.eventTime.toDouble())
                textureView.setDirty(true)
                textureView.requestFrame()
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!directionResolved) {
                    if (hypot(event.x - downX, event.y - downY) >= slopPx) {
                        directionResolved = true
                        pipeline.resolveDirection(
                            event.x.toDouble(), event.y.toDouble(),
                            downX.toDouble(), downY.toDouble(),
                        )
                        if (!armed && pipeline.gestureState.phase == TurnPhase.ARMING) {
                            armed = pipeline.arm(event.eventTime.toDouble())
                        }
                    }
                }
                pipeline.move(pointerId.toLong(), event.x.toDouble(), event.y.toDouble(), event.eventTime.toDouble())
                textureView.requestFrame()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.let { vt ->
                    vt.addMovement(event)
                    vt.computeCurrentVelocity(1000)
                    val vx = vt.getXVelocity(pointerId).toDouble()
                    val scale = if (pipeline.pageRect.width > 1.0) pipeline.pageW / pipeline.pageRect.width else 1.0
                    val outcome = pipeline.release(
                        pointerId.toLong(), event.x.toDouble(), event.y.toDouble(),
                        event.eventTime.toDouble(), velocityPxPerMs = vx * scale / 1000.0,
                    )
                    if (outcome != null) {
                        startSettle(outcome)
                    } else {
                        // Never left PRESSING: snap back to flat.
                        pipeline.clearOutcome()
                        textureView.requestFrame()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                // NO setDirty(false) here: startSettle owns the dirty flag for
                // the settle animation it just started. Clearing dirty on the
                // release path froze the settle after one frame (the render
                // thread consumed the dirty tick before the animation began).
                textureView.requestFrame()
            }
        }
        return true
    }

    /** Kicks the 300 ms ease settle toward the commit/cancel end state. */
    private fun startSettle(outcome: TurnOutcome) {
        // From the last solved curl (if any); otherwise start flat.
        val from = pipeline.lastCurl?.let { pipeline.paramsFor(it) } ?: idleParams()
        val to = when (outcome) {
            TurnOutcome.Commit -> CurlFrameParams(
                axisPoint = Vec2(0.0, 0.0),
                axisNormal = Vec2(1.0, 0.0),
                radius = 0.0,
                pageW = pipeline.pageW,
                pageH = pipeline.pageH,
                halfThickness = 0.35,
                pageVisible = true,
            )

            TurnOutcome.Cancel -> idleParams()
        }
        settleFrom = from
        settleTo = to
        settleIsCommit = outcome == TurnOutcome.Commit
        settleStartMs = System.currentTimeMillis()
        textureView.setDirty(true)
    }

    private fun cancelSettle() {
        if (settleTo != null) {
            settleFrom = null
            settleTo = null
            settleIsCommit = false
            pipeline.clearOutcome()
            textureView.setDirty(true)
        }
    }

    private fun updateStatus() {
        if (isDestroyed || isFinishing) return
        statusView.text = pipeline.statusLine()
        statusView.postDelayed({ updateStatus() }, 66)
    }

    private fun lerpParams(a: CurlFrameParams, b: CurlFrameParams, t: Double): CurlFrameParams {
        fun l(x: Double, y: Double) = x + (y - x) * t
        return CurlFrameParams(
            axisPoint = Vec2(l(a.axisPoint.x, b.axisPoint.x), l(a.axisPoint.y, b.axisPoint.y)),
            axisNormal = Vec2(l(a.axisNormal.x, b.axisNormal.x), l(a.axisNormal.y, b.axisNormal.y)).normalizedOrUnitX(),
            radius = l(a.radius, b.radius),
            pageW = a.pageW,
            pageH = a.pageH,
            halfThickness = a.halfThickness,
            pageVisible = true,
        )
    }

    private fun Vec2.normalizedOrUnitX(): Vec2 {
        val len = kotlin.math.hypot(x, y)
        return if (len.isFinite() && len > 1e-9) Vec2(x / len, y / len) else Vec2(1.0, 0.0)
    }

    private fun easeInOut(t: Double): Double =
        if (t < 0.5) 2.0 * t * t else 1.0 - 2.0 * (1.0 - t) * (1.0 - t)

    override fun onDestroy() {
        // I-8: kill the status bar self-rescheduling loop.
        statusView.removeCallbacks(null)
        textureView.host.stop()
        super.onDestroy()
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x, y)

    private companion object {
        const val SETTLE_MS = 300.0
    }
}
