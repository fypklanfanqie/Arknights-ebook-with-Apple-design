package com.lfq06.arknightsreader.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Seam over [ContentResolver] so import tests can serve fake documents
 * without a real SAF provider.
 */
interface Documents {
    /** Opens [uri] for reading, or null when the uri cannot be resolved. */
    fun openBytes(uri: String): ByteArray?

    /** Display name reported by the provider, or null. */
    fun displayName(uri: String): String?
}

/** Production binding over the real ContentResolver. */
class ResolverDocuments(private val resolver: ContentResolver) : Documents {
    override fun openBytes(uri: String): ByteArray? = try {
        resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    override fun displayName(uri: String): String? = try {
        resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** Test double serving registered bytes. */
class FakeOpenDocuments : Documents {
    private val files = HashMap<String, Pair<ByteArray, String?>>()

    fun register(uri: String, bytes: ByteArray, displayName: String? = null) {
        files[uri] = bytes to displayName
    }

    override fun openBytes(uri: String): ByteArray? = files[uri]?.first

    override fun displayName(uri: String): String? = files[uri]?.second
}

/**
 * Reads from an in-memory registry keyed by pseudo-URIs. Serves both the
 * Robolectric fakes and the asset-based preloader: callers register bytes
 * under any scheme they control (e.g. `asset://builtin/x.txt`).
 */
class RegistryDocuments : Documents {
    private val files = HashMap<String, Pair<ByteArray, String?>>()

    fun register(uri: String, bytes: ByteArray, displayName: String? = null) {
        files[uri] = bytes to displayName
    }

    override fun openBytes(uri: String): ByteArray? = files[uri]?.first

    override fun displayName(uri: String): String? = files[uri]?.second
}
