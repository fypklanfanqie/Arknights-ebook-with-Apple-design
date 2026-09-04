package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.model.BlockKind
import com.lfq06.arknightsreader.model.ContentBlock
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * XHTML -> prose block sanitizer for novel-style EPUBs. Only whitelisted
 * structural tags survive; scripts, styles, iframes, event handlers and
 * remote resources are dropped entirely. The result is a flat list of
 * [ContentBlock]s that the reader can paginate and search.
 *
 * Image handling: packaged (non-remote) images become IMAGE_PLACEHOLDER
 * blocks with their [ContentBlock.imageRef] set; the reader later resolves
 * the ref through the manifest.
 */
object XhtmlSanitizer {

    // Block-level tags we render as one ContentBlock each.
    private const val BLOCK_P = "p"
    private const val BLOCK_QUOTE = "blockquote"

    /** Returns a flat, sanitized list of prose blocks. */
    fun sanitize(xhtml: String, manifestHrefs: Set<String> = emptySet()): List<ContentBlock> {
        val doc = try {
            EpubXml.parse(xhtml.toByteArray(), "xhtml")
        } catch (_: Exception) {
            return emptyList()
        }
        val body = EpubXml.first(doc, "body") ?: return emptyList()
        val blocks = ArrayList<ContentBlock>()
        var order = 0
        walk(body, manifestHrefs, blocks) { order++ }
        return blocks
    }

    private fun walk(
        node: Node,
        manifestHrefs: Set<String>,
        out: MutableList<ContentBlock>,
        nextOrder: () -> Int,
    ) {
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                val tag = el.tagName.substringAfter(':').lowercase()
                when {
                    tag == "script" || tag == "style" || tag == "iframe" || tag == "link" || tag == "head" -> {
                        // dropped entirely
                    }
                    tag == "img" -> {
                        val src = el.getAttribute("src")
                        if (src.isNotEmpty() && isPackaged(src, manifestHrefs)) {
                            out.add(
                                ContentBlock(
                                    id = "", chapterId = "", orderIndex = nextOrder(),
                                    kind = BlockKind.IMAGE_PLACEHOLDER, text = el.getAttribute("alt") ?: "", imageRef = src,
                                ),
                            )
                        }
                    }
                    tag == BLOCK_P || tag == BLOCK_QUOTE -> {
                        val text = collectInlineText(el)
                        if (text.isNotBlank()) {
                            val kind = if (tag == BLOCK_QUOTE) BlockKind.CAPTION else BlockKind.PARAGRAPH
                            out.add(
                                ContentBlock(
                                    id = "", chapterId = "", orderIndex = nextOrder(),
                                    kind = kind, text = text, imageRef = null,
                                ),
                            )
                        }
                    }
                    tag.startsWith("h") && tag.length == 2 && tag[1] in '1'..'6' -> {
                        val text = collectInlineText(el)
                        if (text.isNotBlank()) {
                            out.add(
                                ContentBlock(
                                    id = "", chapterId = "", orderIndex = nextOrder(),
                                    kind = BlockKind.HEADING, text = text, imageRef = null,
                                ),
                            )
                        }
                    }
                    else -> {
                        // Nested block containers (div, section, td): recurse so
                        // inner p/h1 blocks stay separate.
                        walk(el, manifestHrefs, out, nextOrder)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    /** Concatenated text of inline elements inside a block, without handlers. */
    private fun collectInlineText(el: Element): String {
        val sb = StringBuilder()
        appendInline(el, sb)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun appendInline(node: Node, sb: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE -> sb.append(child.textContent)
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    val tag = el.tagName.substringAfter(':').lowercase()
                    // Skip non-inline/non-textual children; keep text of the rest.
                    if (tag !in setOf("script", "style", "iframe", "link")) {
                        appendInline(el, sb)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    /** True when [src] is a book-internal resource (relative, no scheme). */
    private fun isPackaged(src: String, manifestHrefs: Set<String>): Boolean {
        if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//") || src.startsWith("data:")) return false
        return manifestHrefs.isEmpty() || manifestHrefs.any { src in it }
    }
}
