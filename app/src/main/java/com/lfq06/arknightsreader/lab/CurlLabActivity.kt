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
 * Touch pipeline (per event): clientToCanonical -> resolveDragDirection ->
 * Press/Move/Armed/Release into the pure reducer; every drag frame runs
 * constrained Q -> CurlSolver.solve -> CurlMesh.build (reused output) ->
 * mesh upload -> render request. Textures are pre-generated at idle, so a
 * drag performs zero Bitmap work and zero full texture uploads.
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

    // Settle animation state.
    private var settleFrom: CurlFrameParams? = null
    private var settleTo: CurlFrameParams? = null
    private var settleStartMs = 0L
    private var settleRunnable: Runnable? = null

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
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
        textureView.host.frameListener = object : com.lfq06.arknightsreader.turngl.CurlTextureViewHost.FrameListener {
            override fun onPrepareFrame(): Boolean {
                return prepareFrame()
            }
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
        uploadTextures()
    }

    /** Uploads textures to GL once the renderer is ready; safe to call twice. */
    private fun uploadTextures() {
        val fb = frontBitmap ?: return
        val bb = backBitmap ?: return
        if (texturesUploaded) return
        CurlEglFrame.uploadTexture(fb, bb)
        texturesUploaded = true
    }

    private fun prepareFrame(): Boolean {
        // Drag in flight: solve + mesh build + upload on the render thread.
        if (pipeline.gestureState.phase == TurnPhase.DRAGGING ||
            pipeline.gestureState.phase == TurnPhase.PRESSING ||
            pipeline.gestureState.phase == TurnPhase.ARMING
        ) {
            pipeline.frameFor()?.let { params ->
                pipeline.lastMesh?.let { mesh ->
                    textureView.host.updateMesh(mesh)
                }
                drawParams = params
                return true
            }
        }
        // Settle animation in flight.
        settleTo?.let { to ->
            settleFrom?.let { from ->
                val t = ((System.currentTimeMillis() - settleStartMs) / SETTLE_MS).coerceIn(0.0, 1.0)
                val e = easeInOut(t)
                drawParams = lerpParams(from, to, e)
                if (t >= 1.0) {
                    val wasCommit = settleIsCommit
                    settleFrom = null
                    settleTo = null
                    settleIsCommit = false
                    pipeline.clearOutcome()
                    if (wasCommit) {
                        // Reset the page to flat for the next turn (real page
                        // swap is the next task's job).
                        drawParams = idleParams()
                    }
                    runOnUiThread { textureView.setDirty(false) }
                }
                return true
            }
        }
        // Flat idle: draw the page once after params changes.
        drawParams = drawParams ?: idleParams()
        return true
    }

    private var drawParams: CurlFrameParams? = null

    private var settleIsCommit = false

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
                        drawParams = idleParams()
                        pipeline.clearOutcome()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                textureView.setDirty(false)
                textureView.requestFrame()
            }
        }
        return true
    }

    /** Kicks the 300 ms ease settle toward the commit/cancel end state. */
    private fun startSettle(outcome: TurnOutcome) {
        val from = drawParams ?: idleParams()
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
        textureView.host.stop()
        super.onDestroy()
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x, y)

    private companion object {
        const val SETTLE_MS = 300.0
    }
}
