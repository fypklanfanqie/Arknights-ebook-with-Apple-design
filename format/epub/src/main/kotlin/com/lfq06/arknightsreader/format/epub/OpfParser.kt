package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.format.api.ParseException
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Parsed OPF package: metadata plus ordered spine items and manifest refs. */
data class OpfPackage(
    val title: String?,
    val author: String?,
    /** Spine items in reading order: href of each XHTML doc. */
    val spineHrefs: List<String>,
    /** Manifest: id -> href for image/resource resolution. */
    val manifest: Map<String, String>,
)

/**
 * Extracts the OPF path from META-INF/container.xml, then parses the OPF
 * package document into [OpfPackage].
 */
object OpfParser {

    private val entryByName = { entries: List<SafeZip.Entry> -> entries.associateBy { it.name } }

    fun containerOpfPath(entries: List<SafeZip.Entry>): String {
        val map = entryByName(entries)
        val container = map["META-INF/container.xml"]
            ?: throw ParseException("epub missing META-INF/container.xml")
        val doc = EpubXml.parse(container.bytes, "container.xml")
        val rootfile = EpubXml.all(doc, "rootfile").firstOrNull()
            ?: throw ParseException("epub container.xml has no rootfile")
        return rootfile.getAttribute("full-path")
            .takeIf { it.isNotBlank() }
            ?: throw ParseException("epub container.xml rootfile has no full-path")
    }

    fun parseOpf(entries: List<SafeZip.Entry>, opfPath: String): OpfPackage {
        val map = entryByName(entries)
        // OPF hrefs are relative to the OPF directory.
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfEntry = map[opfPath] ?: throw ParseException("epub missing $opfPath")
        val doc = EpubXml.parse(opfEntry.bytes, "opf")

        val title = EpubXml.textOrNull(EpubXml.first(doc, "title"))
        val author = EpubXml.textOrNull(EpubXml.first(doc, "creator"))

        val manifest = HashMap<String, String>()
        for (item in EpubXml.all(doc, "item")) {
            val id = item.getAttribute("id").takeIf { it.isNotBlank() } ?: continue
            val href = item.getAttribute("href")
            manifest[id] = resolvePath(opfDir, href)
        }

        val spine = EpubXml.all(doc, "itemref").mapNotNull { itemref ->
            val idref = itemref.getAttribute("idref")
            manifest[idref]?.takeIf { it.endsWith(".xhtml", ignoreCase = true) || it.endsWith(".html", ignoreCase = true) }
        }

        if (spine.isEmpty()) throw ParseException("epub spine is empty or has no xhtml items")
        return OpfPackage(title = title, author = author, spineHrefs = spine, manifest = manifest)
    }

    /** Joins [dir]/[href] safely (hrefs in EPUB are forward-slash relative). */
    fun resolvePath(dir: String, href: String): String {
        if (href.startsWith('/')) return href.removePrefix("/")
        if (dir.isEmpty()) return href
        return if (href.isEmpty()) dir else "$dir/$href"
    }
}
