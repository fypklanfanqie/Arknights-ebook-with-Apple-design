package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.format.api.ParseException
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Secure EPUB container reader. Enforces per-entry and total decompressed
 * size limits, rejects path traversal (zip slip) and absolute paths, and
 * detects the DRM marker [META-INF/encryption.xml] so encrypted books are
 * explicitly rejected rather than misparsed.
 */
object SafeZip {
    const val MAX_ENTRIES = 10_000
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 // 64 MiB
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 // 8 MiB per file

    private const val ENCRYPTION_MARKER = "META-INF/encryption.xml"

    /** One readable entry from the archive. */
    data class Entry(val name: String, val bytes: ByteArray)

    /**
     * Reads every entry (except the encryption marker) with safety limits.
     * Throws [ParseException] for non-zip input, traversal paths, oversized
     * archives, or encrypted (DRM) content.
     */
    fun readAll(zipBytes: ByteArray): List<Entry> {
        val result = ArrayList<Entry>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                var count = 0
                while (entry != null) {
                    if (++count > MAX_ENTRIES) throw ParseException("epub has too many entries")
                    val name = entry.name ?: ""
                    validateName(name)
                    if (name.equals(ENCRYPTION_MARKER, ignoreCase = true)) {
                        throw ParseException("encrypted/drm-protected epub is not supported")
                    }
                    val size = entry.size
                    if (size > MAX_ENTRY_BYTES) {
                        throw ParseException("epub entry $name exceeds the ${MAX_ENTRY_BYTES} limit")
                    }
                    val bytes = readEntry(zip, name)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw ParseException("epub exceeds the ${MAX_TOTAL_BYTES} limit")
                    result.add(Entry(name, bytes))
                    entry = zip.nextEntry
                }
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            throw ParseException("not a valid epub/zip archive", e)
        }
        return result
    }

    private fun readEntry(zip: ZipInputStream, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            read += n
            if (read > MAX_ENTRY_BYTES) throw ParseException("epub entry $name exceeds the ${MAX_ENTRY_BYTES} limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Rejects absolute paths and any `..` segment that escapes the root. */
    private fun validateName(name: String) {
        if (name.startsWith("/") || name.contains("\\")) {
            throw ParseException("epub entry has an absolute or backslash path: $name")
        }
        val normalized: Path = try {
            Paths.get(name).normalize()
        } catch (e: Exception) {
            throw ParseException("epub entry has an invalid path: $name", e)
        }
        if (normalized.startsWith("..")) {
            throw ParseException("epub entry escapes the archive root: $name")
        }
        // Also reject a ".." anywhere mid-path (e.g. "a/../b").
        for (segment in normalized) {
            if (segment.toString() == "..") {
                throw ParseException("epub entry contains a traversal segment: $name")
            }
        }
    }
}
