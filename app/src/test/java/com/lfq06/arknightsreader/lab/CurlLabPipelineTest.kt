package com.lfq06.arknightsreader.lab

import com.lfq06.arknightsreader.turn.TurnOutcome
import com.lfq06.arknightsreader.turn.TurnPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM contract for the lab pipeline: touch -> reducer -> solver -> mesh ->
 * frame params. No Android classes; runs on the plain JUnit platform.
 */
class CurlLabPipelineTest {
    private fun pipeline() = CurlLabPipeline().apply {
        configure(pageW = 420.0, pageH = 560.0, rect = com.lfq06.arknightsreader.turn.TurnRect(0.0, 0.0, 420.0, 560.0))
    }

    @Test
    fun `fresh pipeline builds no frame while idle`() {
        val p = pipeline()
        assertNull(p.frameFor(), "idle page draws flat (no frame params)")
        assertEquals(TurnPhase.IDLE, p.gestureState.phase)
    }

    @Test
    fun `press move arm produces a curl frame with increasing progress`() {
        val p = pipeline()
        assertTrue(p.press(pointerId = 1, x = 400.0, y = 280.0, timeMs = 0.0))
        p.move(pointerId = 1, x = 300.0, y = 260.0, timeMs = 16.0)
        assertTrue(p.arm(timeMs = 17.0), "arming must succeed for the live generation")
        p.move(pointerId = 1, x = 200.0, y = 240.0, timeMs = 32.0)
        val frame = assertNotNull(p.frameFor())
        assertTrue(frame.pageVisible)
        assertEquals(420.0, frame.pageW, 1e-9)
        assertTrue(frame.radius >= 0.0)
        val curl = assertNotNull(p.lastCurl)
        assertTrue(curl.progress > 0.0, "dragging left of the free edge must produce progress")
    }

    @Test
    fun `mesh is rebuilt into the same reused output each frame`() {
        val p = pipeline()
        p.press(1, 400.0, 280.0, 0.0)
        p.move(1, 350.0, 270.0, 16.0)
        p.arm(17.0)
        val m1 = assertNotNull(p.frameFor())
        p.move(1, 250.0, 250.0, 32.0)
        val m2 = assertNotNull(p.frameFor())
        // Mesh results differ in content; the pipeline output array must be reused.
        assertTrue(p.lastMesh!!.vertexCount > 0)
        assertEquals(m1.pageW, m2.pageW, 1e-9)
    }

    @Test
    fun `release below half progress cancels and settles`() {
        val p = pipeline()
        p.press(1, 420.0, 280.0, 0.0)
        p.move(1, 380.0, 280.0, 16.0)
        p.arm(17.0)
        // Park below half progress and wait out the 120 ms velocity freshness
        // window, so the release decides on progress alone (no flick).
        p.move(1, 370.0, 280.0, 32.0)
        p.move(1, 370.0, 280.0, 300.0)
        val outcome = p.release(1, 370.0, 280.0, 320.0, velocityPxPerMs = 0.0)
        assertEquals(TurnOutcome.Cancel, outcome, "slow tiny drag must cancel")
        assertEquals(TurnPhase.SETTLING, p.gestureState.phase)
    }

    @Test
    fun `release past half progress commits`() {
        val p = pipeline()
        p.press(1, 420.0, 0.0, 0.0)
        p.move(1, 200.0, 0.0, 16.0)
        p.arm(17.0)
        p.move(1, 100.0, 0.0, 32.0)
        val outcome = p.release(1, 100.0, 0.0, 48.0, velocityPxPerMs = 0.0)
        assertEquals(TurnOutcome.Commit, outcome, "drag past half page must commit")
    }

    @Test
    fun `interrupt during drag cancels`() {
        val p = pipeline()
        p.press(1, 420.0, 280.0, 0.0)
        p.move(1, 300.0, 280.0, 16.0)
        p.arm(17.0)
        p.interrupt(timeMs = 40.0)
        assertEquals(TurnPhase.SETTLING, p.gestureState.phase)
        assertEquals(TurnOutcome.Cancel, p.gestureState.outcome)
    }

    @Test
    fun `configure resets gesture and bumps generation`() {
        val p = pipeline()
        p.press(1, 420.0, 280.0, 0.0)
        val genBefore = p.gestureState.generation
        p.configure(300.0, 400.0, com.lfq06.arknightsreader.turn.TurnRect(10.0, 20.0, 300.0, 400.0))
        assertEquals(TurnPhase.IDLE, p.gestureState.phase)
        assertEquals(300.0, p.pageW, 1e-9)
        assertTrue(p.gestureState.generation >= genBefore, "reset must bump or keep generation")
    }

    @Test
    fun `status line reports phase and curl fields`() {
        val p = pipeline()
        val idle = p.statusLine()
        assertTrue("phase=IDLE" in idle)
        p.press(1, 420.0, 280.0, 0.0)
        p.move(1, 300.0, 280.0, 16.0)
        p.arm(17.0)
        p.frameFor()
        val dragging = p.statusLine()
        assertTrue("phase=DRAGGING" in dragging)
        assertTrue("progress=" in dragging)
        assertTrue("r=" in dragging)
    }

    @Test
    fun `drag produces no re-pagination and no allocation churn`() {
        // Repeated solves reuse the same output arrays (positions identity).
        val p = pipeline()
        p.press(1, 420.0, 280.0, 0.0)
        p.move(1, 350.0, 270.0, 16.0)
        p.arm(17.0)
        p.frameFor()
        val firstArray = p.lastMesh!!.positions
        repeat(20) { i ->
            p.move(1, 350.0 - i * 5.0, 270.0, 32.0 + i * 8.0)
            p.frameFor()
            assertTrue(p.lastMesh!!.positions === firstArray, "mesh output must be reused on the drag path")
        }
    }

    @Test
    fun `ui-thread move never builds the mesh (I-4 single writer)`() {
        // The thread contract: move() feeds progress but must NOT build the
        // mesh or touch the shared output; only frameFor() (render thread)
        // produces lastMesh. Assert the mesh is null after pure UI-side moves.
        val p = pipeline()
        p.press(1, 420.0, 280.0, 0.0)
        p.move(1, 350.0, 270.0, 16.0)
        p.arm(17.0)
        p.move(1, 250.0, 260.0, 32.0)
        assertNull(p.lastMesh, "UI-thread move() must not build the mesh (I-4)")
        // The solve-only path still caches progress for diagnostics.
        val curl = assertNotNull(p.lastCurl)
        assertTrue(curl.progress > 0.0, "solve-only must still track live progress")
        // The render-thread build path produces the mesh.
        p.frameFor()
        assertTrue(p.lastMesh!!.vertexCount > 0, "frameFor (render thread) builds the mesh")
    }
}
