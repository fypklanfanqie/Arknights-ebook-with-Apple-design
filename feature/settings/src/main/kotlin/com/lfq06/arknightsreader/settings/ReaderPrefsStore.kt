package com.lfq06.arknightsreader.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lfq06.arknightsreader.model.MotionPreference
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Reader typography preferences with sane clamped ranges. */
data class ReaderTypography(
    val fontSizeSp: Int = 18,
    val lineHeightFactor: Float = 1.6f,
    val fontWeight: Int = 400,
    val marginDp: Int = 16,
) {
    companion object {
        val FONT_SIZE_RANGE = 14..30
        val LINE_HEIGHT_RANGE = 1.2f..2.4f
        val MARGIN_RANGE = 8..40
    }
}

enum class ThemeId { PARCHMENT, DARK, EYE_COMFORT, PURE_BLACK }

enum class GlassMode { FULL, SIMPLIFIED, OFF }

enum class GlassPreset { RESTRAINED, CLEAR, SOFT }

/** Full snapshot of global reader preferences. */
data class ReaderPrefs(
    val typography: ReaderTypography = ReaderTypography(),
    val themeId: ThemeId = ThemeId.PARCHMENT,
    val useCustomBackgroundImage: Boolean = false,
    val soundOn: Boolean = false,
    val hapticsOn: Boolean = false,
    val glassMode: GlassMode = GlassMode.FULL,
    val glassPreset: GlassPreset = GlassPreset.RESTRAINED,
    val motion: MotionPreference = MotionPreference.SYSTEM,
)

/**
 * DataStore-backed global preferences. The store file lives in the app's
 * private storage; tests inject an isolated file name.
 */
class ReaderPrefsStore(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    fileName: String = "reader_prefs",
) {
    private val Context.dataStore by preferencesDataStore(name = fileName)

    private val ds = context.dataStore

    private val fontSize = intPreferencesKey("font_size_sp")
    private val lineHeight = floatPreferencesKey("line_height")
    private val fontWeight = intPreferencesKey("font_weight")
    private val margin = intPreferencesKey("margin_dp")
    private val themeId = stringPreferencesKey("theme_id")
    private val bgImage = booleanPreferencesKey("bg_image")
    private val sound = booleanPreferencesKey("sound_on")
    private val haptics = booleanPreferencesKey("haptics_on")
    private val glassMode = stringPreferencesKey("glass_mode")
    private val glassPreset = stringPreferencesKey("glass_preset")
    private val motion = stringPreferencesKey("motion")

    val prefs: Flow<ReaderPrefs> = ds.data.map { p ->
        ReaderPrefs(
            typography = ReaderTypography(
                fontSizeSp = (p[fontSize] ?: 18).coerceIn(ReaderTypography.FONT_SIZE_RANGE),
                lineHeightFactor = (p[lineHeight] ?: 1.6f).coerceIn(ReaderTypography.LINE_HEIGHT_RANGE),
                fontWeight = p[fontWeight] ?: 400,
                marginDp = (p[margin] ?: 16).coerceIn(ReaderTypography.MARGIN_RANGE),
            ),
            themeId = enumOr(p[themeId], ThemeId.PARCHMENT),
            useCustomBackgroundImage = p[bgImage] ?: false,
            soundOn = p[sound] ?: false,
            hapticsOn = p[haptics] ?: false,
            glassMode = enumOr(p[glassMode], GlassMode.FULL),
            glassPreset = enumOr(p[glassPreset], GlassPreset.RESTRAINED),
            motion = enumOr(p[motion], MotionPreference.SYSTEM),
        )
    }

    suspend fun setTypography(t: ReaderTypography) {
        ds.edit {
            it[fontSize] = t.fontSizeSp.coerceIn(ReaderTypography.FONT_SIZE_RANGE)
            it[lineHeight] = t.lineHeightFactor.coerceIn(ReaderTypography.LINE_HEIGHT_RANGE)
            it[fontWeight] = t.fontWeight
            it[margin] = t.marginDp.coerceIn(ReaderTypography.MARGIN_RANGE)
        }
    }

    suspend fun setThemeId(value: ThemeId) = ds.edit { it[themeId] = value.name }
    suspend fun setUseCustomBackgroundImage(value: Boolean) = ds.edit { it[bgImage] = value }
    suspend fun setSoundOn(value: Boolean) = ds.edit { it[sound] = value }
    suspend fun setHapticsOn(value: Boolean) = ds.edit { it[haptics] = value }
    suspend fun setGlassMode(value: GlassMode) = ds.edit { it[glassMode] = value.name }
    suspend fun setGlassPreset(value: GlassPreset) = ds.edit { it[glassPreset] = value.name }
    suspend fun setMotion(value: MotionPreference) = ds.edit { it[motion] = value.name }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
