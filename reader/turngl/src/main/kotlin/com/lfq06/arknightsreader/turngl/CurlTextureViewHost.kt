package com.lfq06.arknightsreader.turngl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.TextureView

/**
 * Owns the EGL context and the render thread driving a [TextureView]'s
 * SurfaceTexture. Rendering is demand-driven via [CurlTextureHost]: requests
 * coalesce, continuous dirty-mode covers drags/animations, and a quiescent
 * host stops drawing (zero GPU work while the page is flat).
 *
 * Thread model (I-4 contract):
 * - UI thread calls [start]/[stop]/[requestFrame]/[setDirty]/[setFrameParams]
 *   and pushes immutable [CurlFrameParams] snapshots via [setFrameParams]
 *   (atomic swap under [lock]).
 * - A single worker thread owns the EGL surface and ALL GLES calls. Mesh
 *   building ([com.lfq06.arknightsreader.turn.CurlMesh.build] into the shared
 *   [com.lfq06.arknightsreader.turn.CurlMesh.MeshOutput]) and VBO uploads
 *   happen exclusively on this thread, either via [uploadMesh] (called from
 *   the frame listener during prepare) or the legacy pending queue drained in
 *   [consumePendingMesh].
 * - [FrameListener.onPrepareFrame] runs on the render thread and RETURNS the
 *   params to draw this tick (C-3): the loop draws the returned snapshot, not
 *   a previously cached one, so settle-lerped or freshly solved params always
 *   reach the GPU in the same tick they were computed.
 */
class CurlTextureViewHost(
    private val frameHost: CurlTextureHost = CurlTextureHost(),
    private val rendererFactory: () -> CurlGLRenderer = { CurlGLRenderer() },
    private val buffersFactory: () -> CurlMeshBuffers = { CurlMeshBuffers() },
    /**
     * Test seam: when set, replaces [CurlEglFrame.draw] in [drawFrame]. The
     * production value is null and the real draw path runs.
     */
    private val drawOverride: ((CurlFrameParams, CurlTextureViewHost) -> Unit)? = null,
) : TextureView.SurfaceTextureListener {

    /**
     * Prepares one frame on the render thread. Returns the params to draw, or
     * null to skip drawing entirely. The returned snapshot is what reaches
     * [CurlEglFrame.draw] in the same tick (C-3).
     */
    fun interface FrameListener {
        fun onPrepareFrame(): CurlFrameParams?
    }

    /** Called on the render thread once the GL program is ready. */
    var onEglReady: (() -> Unit)? = null

    var frameListener: FrameListener? = null

    private val lock = Any()
    private var frameParams: CurlFrameParams = CurlFrameParams.idle()

    @Volatile
    private var running = false

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    /** Surface pixel size; written from UI callbacks, read on the render thread. */
    @Volatile
    private var surfaceW = 1

    @Volatile
    private var surfaceH = 1

    // EGL handles, owned by the worker thread. Initialized lazily inside
    // loop(): touching EGL14 sentinels during class init would break JVM unit
    // tests (the stub android.jar throws on EGL constants).
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // Render resources. Guarded by [rebuildLock] on the start path; every GL
    // use is on the worker thread. Rebuilt on [start] when a previous cycle
    // released them (I-6: destroy -> re-available must keep working).
    private val rebuildLock = Any()
    private var renderer: CurlGLRenderer = rendererFactory()
    private var buffers: CurlMeshBuffers = buffersFactory()

    /** Latest frame params; swapped atomically under [lock]. */
    fun setFrameParams(params: CurlFrameParams) {
        synchronized(lock) { frameParams = params }
        frameHost.requestFrame()
    }

    /**
     * Render-thread direct path: streams a freshly built mesh into the VBO.
     * Must be called from the frame listener (render thread) so that
     * [com.lfq06.arknightsreader.turn.CurlMesh.MeshOutput] is only ever
     * written by the render thread (I-4).
     */
    fun uploadMesh(result: com.lfq06.arknightsreader.turn.CurlMesh.MeshResult) {
        buffers.setCurl(result)
    }

    /**
     * Legacy UI-thread hand-off: stores the mesh; the render thread streams it
     * into the VBO on its next tick. Kept for callers that build off-thread;
     * the lab now builds on the render thread and uses [uploadMesh].
     */
    fun updateMesh(result: com.lfq06.arknightsreader.turn.CurlMesh.MeshResult) {
        synchronized(meshLock) { pendingMesh = result }
        frameHost.requestFrame()
    }

    private val meshLock = Any()
    private var pendingMesh: com.lfq06.arknightsreader.turn.CurlMesh.MeshResult? = null

    private fun consumePendingMesh() {
        val mesh = synchronized(meshLock) {
            val m = pendingMesh
            pendingMesh = null
            m
        } ?: return
        buffers.setCurl(mesh)
    }

    fun requestFrame() = frameHost.requestFrame()

    fun setDirty(value: Boolean) = frameHost.setDirty(value)

    /**
     * Spawns the render thread once a SurfaceTexture is available.
     * [width]/[height] are the surface's initial pixel size (C-4 viewport).
     */
    fun start(surface: SurfaceTexture, width: Int, height: Int) {
        if (running) return
        // I-6: a previous destroy cycle released the renderer/buffers; rebuild
        // them so the new EGL context gets fresh GL handles.
        synchronized(rebuildLock) {
            if (renderer.isReleased) {
                renderer = rendererFactory()
                buffers = buffersFactory()
            }
        }
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        surfaceTexture = surface
        running = true
        worker = Thread({ loop(surface) }, "curl-render").apply {
            setDaemon(true)
            start()
        }
    }

    /**
     * Stops the render thread and releases EGL + GL handles. Idempotent.
     * I-5: joins the worker (bounded) before returning, so the caller (UI
     * thread, from onSurfaceTextureDestroyed) knows no further GLES work
     * references the SurfaceTexture after this call returns true.
     */
    fun stop() {
        frameHost.stop()
        running = false
        val w = synchronized(rebuildLock) {
            val current = worker
            worker = null
            current
        }
        w?.interrupt()
        // Bounded join: a wedged EGL teardown must not freeze the UI thread
        // forever; 2 s is far beyond any sane teardown.
        w?.join(2000)
        surfaceTexture = null
    }

    fun isRunning(): Boolean = running

    // ---- SurfaceTextureListener ----

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        start(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // EGL window surface tracks the SurfaceTexture buffer size; update the
        // viewport dimensions and redraw (C-4).
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        frameHost.requestFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // I-5: stop() joins the worker before returning, so returning true
        // (release the SurfaceTexture) is safe: the render thread no longer
        // touches it.
        stop()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Content frames are pushed by requestFrame only; nothing to do here.
    }

    // ---- render loop ----

    private fun loop(surface: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) {
            running = false
            return
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] < 1) {
            EGL14.eglTerminate(display)
            running = false
            return
        }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            running = false
            return
        }
        val winSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (winSurface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, winSurface, winSurface, context)) {
            eglDisplay = display
            eglContext = context
            eglSurface = winSurface
            releaseEgl()
            running = false
            return
        }
        eglDisplay = display
        eglContext = context
        eglSurface = winSurface
        synchronized(rebuildLock) {
            renderer.initialize()
            buffers.ensureCapacity(CurlEglFrame.DEFAULT_COLS, CurlEglFrame.DEFAULT_ROWS)
        }
        if (!renderer.isReady) {
            releaseEgl()
            running = false
            return
        }
        CurlEglFrame.setup(renderer, buffers)
        onEglReady?.invoke()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        while (running) {
            val drew = tick()
            if (!drew) {
                try {
                    Thread.sleep(IDLE_SLEEP_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        synchronized(rebuildLock) {
            buffers.release()
            renderer.release()
        }
        releaseEgl()
    }

    /**
     * Returns true if a frame was drawn this tick. C-3: the params drawn are
     * the ones [FrameListener.onPrepareFrame] RETURNS this tick (freshly
     * solved or settle-lerped), never a stale cached snapshot.
     */
    private fun tick(): Boolean {
        consumePendingMesh()
        if (!frameHost.shouldRender(synchronized(lock) { frameParams })) return false
        frameHost.drainPending()
        val params = frameListener?.onPrepareFrame() ?: return false
        if (drawOverride != null) {
            // Test path: fully replaces the real draw (no GL/EGL touched).
            drawOverride(params, this)
        } else {
            drawFrame(params)
        }
        return true
    }

    private fun drawFrame(params: CurlFrameParams) {
        val surface = eglSurface ?: return
        val display = eglDisplay ?: return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!params.pageVisible) {
            EGL14.eglSwapBuffers(display, surface)
            return
        }
        // Draw pipeline (two passes, front then back) is delegated to the
        // renderer bound to this thread's EGL context; see CurlEglFrame.
        CurlEglFrame.draw(params, renderer, buffers, surface, surfaceW, surfaceH)
    }

    /**
     * Test-visible tick core: shared by the real render loop and the JVM
     * contract test for C-3. Returns true if a frame was drawn.
     */
    internal fun tickForTest(): Boolean = tick()

    /** Test-only view of the current renderer (I-6 rebuild assertions). */
    internal fun rendererForTest(): CurlGLRenderer = synchronized(rebuildLock) { renderer }

    private fun releaseEgl() {
        val display = eglDisplay ?: return
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            val surface = eglSurface
            if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            val context = eglContext
            if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    private var worker: Thread? = null

    private companion object {
        const val IDLE_SLEEP_MS = 24L
    }
}
