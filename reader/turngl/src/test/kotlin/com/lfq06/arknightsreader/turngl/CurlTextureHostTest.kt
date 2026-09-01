package com.lfq06.arknightsreader.turngl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlTextureHostTest {
    /** Minimal idle-state params used by the host loop tests. */
    private val idle = CurlFrameParams.idle()

    @Test
    fun `loop starts idle and does not render without requests`() {
        val host = CurlTextureHost()
        assertFalse(host.shouldRender(idle), "fresh host must be quiescent")
        assertEquals(0, host.drainPending())
    }

    @Test
    fun `request frame makes exactly one render tick`() {
        val host = CurlTextureHost()
        host.requestFrame()
        assertEquals(1, host.drainPending())
        assertFalse(host.shouldRender(idle), "drained host must go back to sleep")
        assertEquals(0, host.drainPending())
    }

    @Test
    fun `coalesces burst requests into one tick`() {
        val host = CurlTextureHost()
        repeat(10) { host.requestFrame() }
        assertEquals(1, host.drainPending(), "burst must coalesce")
    }

    @Test
    fun `continuous renders while marked dirty then sleeps after undirty`() {
        val host = CurlTextureHost()
        host.setDirty(true)
        assertTrue(host.shouldRender(idle))
        host.setDirty(false)
        assertFalse(host.shouldRender(idle))
    }

    @Test
    fun `stopped host refuses frames and reports stopped`() {
        val host = CurlTextureHost()
        host.stop()
        assertTrue(host.isStopped)
        host.requestFrame()
        assertEquals(0, host.drainPending(), "stopped host must not queue frames")
        host.setDirty(true)
        assertFalse(host.shouldRender(idle))
    }

    @Test
    fun `stop is idempotent`() {
        val host = CurlTextureHost()
        host.stop()
        host.stop()
        assertTrue(host.isStopped)
    }
}
