package com.lfq06.arknightsreader.turngl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.view.TextureView

/**
 * TextureView subclass wiring [CurlTextureViewHost] lifecycle to the view's
 * SurfaceTexture events. Touch handling is deliberately NOT done here: the
 * interaction layer (CurlLabActivity) routes gestures into the pure reducer
 * and pushes frame params back down.
 */
class CurlTextureView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr) {

    val host = CurlTextureViewHost(this)

    init {
        isOpaque = false
        surfaceTextureListener = host
    }

    /** True once the render thread is up and the GL program is ready. */
    fun isRendererRunning(): Boolean = host.isRunning()

    /** Convenience passthrough for the interaction layer. */
    fun setFrameParams(params: CurlFrameParams) = host.setFrameParams(params)

    fun requestFrame() = host.requestFrame()

    fun setDirty(value: Boolean) = host.setDirty(value)

    override fun onDetachedFromWindow() {
        host.stop()
        super.onDetachedFromWindow()
    }

    /** Exposed for tests: simulate the surface-available callback. */
    fun attachForTest(surface: SurfaceTexture, width: Int, height: Int) {
        host.onSurfaceTextureAvailable(surface, width, height)
    }
}
