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
 * Thread model:
 * - UI thread calls [start]/[stop]/[requestFrame]/[setDirty]/[setFrameParams].
 * - A single worker thread owns the EGL surface and all GLES calls.
 * - [onFrameParams] snapshots are swapped atomically; the worker reads the
 *   latest snapshot each tick.
 */
class CurlTextureViewHost(
    private val textureView: TextureView,
    private val frameHost: CurlTextureHost = CurlTextureHost(),
) : TextureView.SurfaceTextureListener {

    interface FrameListener {
        /**
         * Called on the render thread before each drawn frame. Implementations
         * update mesh buffers / renderer state; return false to skip drawing.
         */
        fun onPrepareFrame(): Boolean
    }

    /** Called on the render thread once the GL program is ready. */
    var onEglReady: (() -> Unit)? = null

    var frameListener: FrameListener? = null

    private val lock = Any()
    private var worker: Thread? = null
    private var frameParams: CurlFrameParams = CurlFrameParams.idle()

    @Volatile
    private var running = false

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    // EGL handles, owned by the worker thread.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val renderer = CurlGLRenderer()
    private val buffers = CurlMeshBuffers()

    /** Latest frame params; swapped atomically under [lock]. */
    fun setFrameParams(params: CurlFrameParams) {
        synchronized(lock) { frameParams = params }
        frameHost.requestFrame()
    }

    /**
     * Thread-safe mesh hand-off: the UI thread stores the latest built mesh;
     * the render thread streams it into the VBO on its next tick. Keeping the
     * swap on the UI side avoids blocking the touch path on GL work.
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
        CurlEglFrame.updateMesh(buffers, mesh)
    }

    fun requestFrame() = frameHost.requestFrame()

    fun setDirty(value: Boolean) = frameHost.setDirty(value)

    /** Spawns the render thread once a SurfaceTexture is available. */
    fun start(surface: SurfaceTexture) {
        if (running) return
        surfaceTexture = surface
        running = true
        worker = Thread({ loop(surface) }, "curl-render").apply {
            setDaemon(true)
            start()
        }
    }

    /** Stops the render thread and releases EGL + GL handles. Idempotent. */
    fun stop() {
        frameHost.stop()
        running = false
        worker?.interrupt()
        worker = null
        surfaceTexture = null
    }

    fun isRunning(): Boolean = running

    // ---- SurfaceTextureListener ----

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        start(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // EGL window surface tracks the SurfaceTexture buffer size; a resize
        // just needs a redraw.
        frameHost.requestFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stop()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Content frames are pushed by requestFrame only; nothing to do here.
    }

    // ---- render loop ----

    private fun loop(surface: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(eglDisplay, IntArray(1), 0, IntArray(1), 0)) {
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
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] < 1) {
            EGL14.eglTerminate(eglDisplay)
            running = false
            return
        }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(eglDisplay)
            running = false
            return
        }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            releaseEgl()
            running = false
            return
        }
        renderer.initialize()
        if (!renderer.isReady) {
            releaseEgl()
            running = false
            return
        }
        buffers.ensureCapacity(CurlEglFrame.DEFAULT_COLS, CurlEglFrame.DEFAULT_ROWS)
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
        buffers.release()
        renderer.release()
        releaseEgl()
    }

    /** Returns true if a frame was drawn this tick. */
    private fun tick(): Boolean {
        consumePendingMesh()
        val params = synchronized(lock) { frameParams }
        if (!frameHost.shouldRender(params)) return false
        frameHost.drainPending()
        if (frameListener?.onPrepareFrame() == false) return false
        drawFrame(params)
        return true
    }

    private fun drawFrame(params: CurlFrameParams) {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!params.pageVisible) {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            return
        }
        // Draw pipeline (two passes, front then back) is delegated to the
        // renderer bound to this thread's EGL context; see CurlEglFrame.
        CurlEglFrame.draw(params, renderer, buffers, eglSurface)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val IDLE_SLEEP_MS = 24L
    }
}
