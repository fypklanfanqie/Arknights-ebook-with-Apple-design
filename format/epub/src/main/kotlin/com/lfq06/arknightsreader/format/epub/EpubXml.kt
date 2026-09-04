package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.format.api.ParseException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * XXE-safe XML parsing: DOCTYPE declarations and external entities are
 * hard-disabled so a malicious epub cannot read local files or expand
 * entities. Every lookup uses namespace-aware local-name matching because
 * EPUB2/EPUB3 documents use varying namespace prefixes.
 */
object EpubXml {

    fun parse(bytes: ByteArray, what: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setXIncludeAware(false)
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw ParseException("external entity resolution disabled in $what") }
        builder.parse(ByteArrayInputStream(bytes))
    } catch (e: ParseException) {
        throw e
    } catch (e: Exception) {
        throw ParseException("invalid xml in $what", e)
    }

    /** First descendant element with the given local name (depth-first). */
    fun first(root: Node, localName: String): Element? {
        val walk = root.firstChild
        var node = walk
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == localName) return node as Element
            val nested = first(node, localName)
            if (nested != null) return nested
            node = node.nextSibling
        }
        return null
    }

    /** All descendant elements with the given local name, in document order. */
    fun all(root: Node, localName: String): List<Element> {
        val out = ArrayList<Element>()
        collect(root, localName, out)
        return out
    }

    private fun collect(node: Node, localName: String, out: MutableList<Element>) {
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                if (child.localName == localName) out.add(child as Element)
                collect(child, localName, out)
            }
            child = child.nextSibling
        }
    }

    /** Direct child element with the given local name. */
    fun child(parent: Element, localName: String): Element? {
        var node = parent.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == localName) return node as Element
            node = node.nextSibling
        }
        return null
    }

    /** Trimmed text content of the element, or null when empty. */
    fun textOrNull(element: Element?): String? =
        element?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
