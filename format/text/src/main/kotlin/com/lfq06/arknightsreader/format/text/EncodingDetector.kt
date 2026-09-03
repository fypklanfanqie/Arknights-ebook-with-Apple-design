package com.lfq06.arknightsreader.format.text

import java.nio.charset.Charset

/**
 * Text encoding detection for user-supplied files: BOM first, then GB18030
 * heuristics for CJK documents without a BOM, then UTF-8.
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
        if (looksLikeGb18030(header)) return GB18030
        return Charsets.UTF_8
    }

    /**
     * GB18030 lead bytes: 0x81-0xFE start a two-byte sequence whose second
     * byte is 0x40-0xFE (excluding 0x7F). Valid pure-ASCII files never enter
     * this branch because they contain no high bytes. UTF-8 CJK text would
     * produce E4-E9 leads with 0x80-0xBF continuations — the 0x40-0x7E range
     * check on the second byte is what separates real GB18030 from UTF-8
     * (UTF-8 continuation bytes never land in 0x40-0x7E).
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
