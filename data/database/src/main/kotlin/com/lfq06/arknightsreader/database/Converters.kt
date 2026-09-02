package com.lfq06.arknightsreader.database

import androidx.room.TypeConverter
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.LayoutMode
import com.lfq06.arknightsreader.model.MotionPreference
import com.lfq06.arknightsreader.model.ReadingCapabilities
import com.lfq06.arknightsreader.model.TextAlign
import com.lfq06.arknightsreader.model.TurnStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges model enums/value objects to storable columns. ReadingCapabilities
 * serializes as compact JSON so new capability flags default gracefully when
 * older rows are read back.
 */
class Converters {

    @TypeConverter
    fun bookFormatToString(value: BookFormat): String = value.name

    @TypeConverter
    fun stringToBookFormat(value: String): BookFormat =
        runCatching { BookFormat.valueOf(value) }.getOrDefault(BookFormat.UNKNOWN)

    @TypeConverter
    fun textAlignToString(value: TextAlign): String = value.name

    @TypeConverter
    fun stringToTextAlign(value: String): TextAlign =
        runCatching { TextAlign.valueOf(value) }.getOrDefault(TextAlign.LEFT)

    @TypeConverter
    fun motionPreferenceToString(value: MotionPreference): String = value.name

    @TypeConverter
    fun stringToMotionPreference(value: String): MotionPreference =
        runCatching { MotionPreference.valueOf(value) }.getOrDefault(MotionPreference.SYSTEM)

    @TypeConverter
    fun layoutModeToString(value: LayoutMode): String = value.name

    @TypeConverter
    fun stringToLayoutMode(value: String): LayoutMode =
        runCatching { LayoutMode.valueOf(value) }.getOrDefault(LayoutMode.SINGLE)

    @TypeConverter
    fun capabilitiesToJson(value: ReadingCapabilities): String = JSONObject().apply {
        put("reflow", value.reflow)
        put("font", value.font)
        put("background", value.background)
        put("search", value.search)
        put("annotate", value.annotate)
        put("turnStyles", JSONArray(value.turnStyles.map { it.name }))
    }.toString()

    @TypeConverter
    fun jsonToCapabilities(value: String): ReadingCapabilities = runCatching {
        val obj = JSONObject(value)
        val styles = mutableListOf<TurnStyle>()
        val arr = obj.optJSONArray("turnStyles") ?: JSONArray()
        for (i in 0 until arr.length()) {
            runCatching { TurnStyle.valueOf(arr.getString(i)) }.getOrNull()?.let { styles += it }
        }
        ReadingCapabilities(
            reflow = obj.optBoolean("reflow", false),
            font = obj.optBoolean("font", false),
            background = obj.optBoolean("background", false),
            search = obj.optBoolean("search", false),
            annotate = obj.optBoolean("annotate", false),
            turnStyles = styles,
        )
    }.getOrDefault(ReadingCapabilities())
}
