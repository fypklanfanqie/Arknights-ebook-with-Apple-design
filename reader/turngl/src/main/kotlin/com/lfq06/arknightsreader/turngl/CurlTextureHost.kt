package com.lfq06.arknightsreader.turngl

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame-pacing brain for the render thread: callers "request frames" from any
 * thread; the render loop drains the pending count each tick and stops
 * rendering entirely when quiescent and not dirty.
 *
 * Semantics:
 * - A burst of requests coalesces into one tick (pending saturates at 1).
 * - While [setDirty] is true, every tick renders (animation in flight).
 * - After [stop], no further rendering happens; state is terminal.
 *
 * This class holds no GL or Android state, so the "render only when needed"
 * rule is unit-testable without an emulator.
 */
class CurlTextureHost {
    private val pending = AtomicInteger(0)
    private val dirty = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    val isStopped: Boolean get() = stopped.get()

    /** Requests one render pass; coalesces with any pending pass. */
    fun requestFrame() {
        if (stopped.get()) return
        pending.set(1)
    }

    /** Marks continuous rendering (drag/animation) on or off. */
    fun setDirty(value: Boolean) {
        if (stopped.get()) return
        dirty.set(value)
    }

    /**
     * Whether the render loop should draw this tick, given the current frame
     * params. Dirty mode (drag/animation in flight) always renders; a one-shot
     * request renders only when the page is visible, so an idle hidden frame
     * never wakes the GPU.
     */
    fun shouldRender(params: CurlFrameParams): Boolean {
        if (stopped.get()) return false
        if (dirty.get()) return true
        return params.pageVisible && pending.get() > 0
    }

    /** Consumes the pending request; returns how many were queued (0 or 1). */
    fun drainPending(): Int = pending.getAndSet(0)

    /** Terminal state: rejects all future requests. Idempotent. */
    fun stop() {
        stopped.set(true)
        pending.set(0)
        dirty.set(false)
    }
}
