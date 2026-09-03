package com.lfq06.arknightsreader.format.api

import com.lfq06.arknightsreader.model.BookFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatModuleTest {

    @Test
    fun `probe result carries format and confidence`() {
        val result = FormatProbeResult(likelyFormat = BookFormat.TXT, confidence = 0.9)
        assertEquals(BookFormat.TXT, result.likelyFormat)
        assertEquals(0.9, result.confidence, 1e-9)
    }

    @Test
    fun `parse exception carries message and cause`() {
        val cause = IllegalStateException("boom")
        val ex = ParseException("bad file", cause)
        assertEquals("bad file", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `limits are defense in depth magnitudes`() {
        assertTrue(FormatLimits.MAX_SOURCE_BYTES >= FormatLimits.CHUNK_BYTES)
        assertTrue(FormatLimits.MAX_CHAPTERS > 0)
        assertTrue(FormatLimits.MAX_BLOCKS > FormatLimits.MAX_CHAPTERS)
    }
}
