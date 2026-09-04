package com.lfq06.arknightsreader.preload

import android.content.Context
import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.importer.ImportService
import com.lfq06.arknightsreader.importer.RegistryDocuments
import kotlinx.coroutines.flow.toList

/**
 * Loads preinstalled sample books bundled with the debug APK exactly once.
 *
 * CONTENT NOTICE: the bundled Arknights story texts are community-archived
 * from PRTS for LOCAL, NON-COMMERCIAL reading validation only; they are not
 * an official distribution and must never ship in a public release build.
 */
object PreloadManager {
    private const val PREF = "preload"
    private const val KEY_DONE = "builtin_loaded_v1"
    private const val BUILTIN_DIR = "builtin"

    /**
     * Imports every supported asset under [BUILTIN_DIR] through the normal
     * import pipeline. Returns how many books were newly added.
     */
    suspend fun ensureLoaded(context: Context, db: AppDatabase): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return 0

        val registry = RegistryDocuments()
        val names = context.assets.list(BUILTIN_DIR).orEmpty().filter(::isSupported)
        for (name in names) {
            val bytes = context.assets.open("$BUILTIN_DIR/$name").use { it.readBytes() }
            registry.register("asset://builtin/$name", bytes, displayName = name)
        }

        val importer = ImportService(context, db, registry)
        var added = 0
        for (name in names) {
            val events = importer.import("asset://builtin/$name").toList()
            if (events.last() is ImportService.ImportProgress.Done) added += 1
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        return added
    }

    private fun isSupported(name: String): Boolean =
        name.endsWith(".txt", true) || name.endsWith(".md", true) || name.endsWith(".epub", true)
}
