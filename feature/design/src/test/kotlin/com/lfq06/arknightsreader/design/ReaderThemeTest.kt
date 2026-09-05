package com.lfq06.arknightsreader.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderThemeTest {

    @Test
    fun `every theme meets the 4_5 to 1 prose contrast floor`() {
        for (id in ReaderThemeId.entries) {
            val palette = ReaderPalettes.forId(id)
            assertTrue(
                contrastRatio(palette.prose, palette.paper) >= 4.5f,
                "${palette} prose/paper contrast must be >= 4.5:1, got ${contrastRatio(palette.prose, palette.paper)}",
            )
        }
    }

    @Test
    fun `contrast ratio is symmetric and bounded`() {
        val ratio = contrastRatio(ReaderPalettes.PARCHMENT.prose, ReaderPalettes.PARCHMENT.paper)
        val reverse = contrastRatio(ReaderPalettes.PARCHMENT.paper, ReaderPalettes.PARCHMENT.prose)
        assertEquals(ratio, reverse, 1e-4f)
        assertTrue(ratio in 1f..21f)
    }

    @Test
    fun `same color has ratio 1`() {
        val c = androidx.compose.ui.graphics.Color(0xFF123456.toInt())
        assertEquals(1f, contrastRatio(c, c), 1e-4f)
    }
}

private typealias Color = androidx.compose.ui.graphics.Color
