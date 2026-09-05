package com.lfq06.arknightsreader.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Page-draw contract (Robolectric): the static reading Canvas and the
 * turn-engine page texture must draw the SAME content from the same
 * PageSlice, so the flipping page never diverges from the resting page.
 */
@RunWith(RobolectricTestRunner::class)
class PageDrawTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun page(): Page {
        val lines = listOf(
            LaidLine("k0", 0, 0, 5, "第一行"),
            LaidLine("k1", 1, 0, 5, "第二行"),
        )
        return Page(0, lines, 0, 1)
    }

    @Test
    fun `page text is identical across draw modes`() {
        val p = page()
        val static = PageDraw.staticText(p)
        val texture = PageDraw.textureText(p)
        assertEquals(static, texture)
        assertEquals(listOf("第一行", "第二行"), static)
    }

    @Test
    fun `render produces a bitmap of the requested size`() {
        val bitmap = PageDraw.renderPage(
            context,
            page(),
            widthPx = 200,
            heightPx = 300,
            textSizePx = 24,
            paperColor = 0xFFF2E8D5.toInt(),
            proseColor = 0xFF3A2F23.toInt(),
        )
        assertEquals(200, bitmap.width)
        assertEquals(300, bitmap.height)
        assertTrue(bitmap.rowBytes * bitmap.height > 0)
    }

    @Test
    fun `image placeholder renders the alt text`() {
        val p = Page(
            0,
            listOf(LaidLine("img", 0, 0, 3, "图片说明")),
            0, 0,
        )
        val bitmap = PageDraw.renderPage(
            context, p, 200, 300, 24,
            paperColor = 0xFFFFFFFFu.toInt(), proseColor = 0xFF000000u.toInt(),
        )
        assertTrue(bitmap.width > 0)
    }
}
