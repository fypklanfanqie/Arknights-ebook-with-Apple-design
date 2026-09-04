package com.lfq06.arknightsreader.format.text

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Text encoding detection for user-supplied files: BOM first, then strict
 * UTF-8 validation (UTF-8 CJK is far more common than GB18030 and strict
 * validation cannot false-positive), then a GB18030 lead/trail heuristic for
 * remaining non-UTF-8 byte streams, then a UTF-8 fallback for plain ASCII.
 */
object EncodingDetector {

    private val GB18030: Charset = charset("GB18030")

    fun detect(header: ByteArray): Charset {
        if (header.size >= 3 && header[0] == 0xEF.toByte() && header[1] == 0xBB.toByte() && header[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (header.size >= 2 && header[0] == 0xFE.toByte() && header[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (isValidUtf8(header)) return Charsets.UTF_8
        if (looksLikeGb18030(header)) return GB18030
        return Charsets.UTF_8
    }

    /** Strict UTF-8 validation (REPORT semantics) over the sniffed bytes. */
    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) {
        false
    }

    /**
     * GB18030 lead bytes: 0x81-0xFE start a two-byte sequence whose second
     * byte is 0x40-0xFE (excluding 0x7F). Only consulted after strict UTF-8
     * validation failed, so UTF-8 CJK never reaches this heuristic.
     */
    private fun looksLikeGb18030(header: ByteArray): Boolean {
        var i = 0
        var pairs = 0
        while (i < header.size) {
            val b = header[i].toInt() and 0xFF
            when {
                b < 0x80 -> i += 1 // ASCII run
                b in 0x81..0xFE -> {
                    if (i + 1 >= header.size) return pairs > 0
                    val next = header[i + 1].toInt() and 0xFF
                    if (next !in 0x40..0xFE || next == 0x7F) return false
                    pairs += 1
                    i += 2
                }
                else -> return false // 0x80 exactly is invalid lead
            }
        }
        return pairs > 0
    }
}
