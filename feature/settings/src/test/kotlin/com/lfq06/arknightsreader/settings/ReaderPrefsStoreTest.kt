package com.lfq06.arknightsreader.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Global preferences contract (Robolectric + real DataStore files in a temp
 * dir): defaults on first read, round-trip persistence, and glass/feedback
 * defaults (both default OFF per product decision).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderPrefsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newStore() = ReaderPrefsStore(
        context,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
    )

    @Test
    fun `defaults on first read`() = runTest {
        val store = newStore()
        val prefs = store.prefs.first()
        assertEquals(ReaderTypography(), prefs.typography)
        assertEquals(ThemeId.PARCHMENT, prefs.themeId)
        assertFalse(prefs.useCustomBackgroundImage)
        assertFalse("sound defaults OFF", prefs.soundOn)
        assertFalse("haptics default OFF", prefs.hapticsOn)
        assertEquals(GlassMode.FULL, prefs.glassMode)
        assertEquals(GlassPreset.RESTRAINED, prefs.glassPreset)
    }

    @Test
    fun `typography round-trips`() = runTest {
        val store = newStore()
        store.setTypography(ReaderTypography(fontSizeSp = 24, lineHeightFactor = 1.8f, marginDp = 28))
        assertEquals(24, store.prefs.first().typography.fontSizeSp)
        assertEquals(1.8f, store.prefs.first().typography.lineHeightFactor, 1e-6f)
        assertEquals(28, store.prefs.first().typography.marginDp)
    }

    @Test
    fun `theme and glass settings round-trip`() = runTest {
        val store = newStore()
        store.setThemeId(ThemeId.PURE_BLACK)
        store.setGlassMode(GlassMode.OFF)
        store.setGlassPreset(GlassPreset.SOFT)
        val prefs = store.prefs.first()
        assertEquals(ThemeId.PURE_BLACK, prefs.themeId)
        assertEquals(GlassMode.OFF, prefs.glassMode)
        assertEquals(GlassPreset.SOFT, prefs.glassPreset)

        store.setGlassMode(GlassMode.SIMPLIFIED)
        assertTrue(store.prefs.first().glassMode == GlassMode.SIMPLIFIED)
    }

    @Test
    fun `feedback toggles round-trip`() = runTest {
        val store = newStore()
        store.setSoundOn(true)
        store.setHapticsOn(true)
        assertTrue(store.prefs.first().soundOn)
        assertTrue(store.prefs.first().hapticsOn)
    }

    @Test
    fun `typography values are clamped to sane ranges`() = runTest {
        val store = newStore()
        store.setTypography(ReaderTypography(fontSizeSp = 100, lineHeightFactor = 9f, marginDp = 999))
        val clamped = store.prefs.first().typography
        assertTrue(clamped.fontSizeSp in ReaderTypography.FONT_SIZE_RANGE)
        assertTrue(clamped.lineHeightFactor in ReaderTypography.LINE_HEIGHT_RANGE)
        assertTrue(clamped.marginDp in ReaderTypography.MARGIN_RANGE)
    }
}
