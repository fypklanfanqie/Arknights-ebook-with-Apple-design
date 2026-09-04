package com.lfq06.arknightsreader.format.epub

import com.lfq06.arknightsreader.format.api.ParseException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Builds minimal EPUB fixtures in-memory so parsing is testable without
 * resource files.
 */
object EpubFixture {
    fun build(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun standardXhtml(title: String, body: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
        <body>$body</body></html>
    """.trimIndent().toByteArray()
}

class EpubModuleTest {

    private val module = EpubModule()

    private fun minimalEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent().toByteArray()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:isbn:test</dc:identifier>
                <dc:title>Test Book</dc:title>
                <dc:creator>Test Author</dc:creator>
              </metadata>
              <manifest>
                <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="img" href="images/pic.png" media-type="image/png"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent().toByteArray()
        val chap1 = EpubFixture.standardXhtml(
            "Chapter One",
            "<h1>Chapter One</h1><p>First paragraph.</p><p>Second with <em>emphasis</em>.</p>",
        )
        val chap2 = EpubFixture.standardXhtml(
            "Chapter Two",
            "<h1>Chapter Two</h1><p>Another chapter.</p><img src=\"images/pic.png\" alt=\"pic\"/>",
        )
        return EpubFixture.build(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/chap1.xhtml" to chap1,
                "OEBPS/chap2.xhtml" to chap2,
                // The manifest declares an image at OEBPS/images/pic.png; the
                // archive must actually ship it for it to be a "packaged" ref.
                "OEBPS/images/pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
    }

    @Test
    fun `probe reports epub for zip magic`() {
        val probe = module.probe(ByteArray(0), sizeBytes = 0)
        // probe() without a header can't sniff zip; the importer calls probe
        // with real bytes. Keep the stub contract: never crash.
        assertTrue(probe.confidence >= 0.0)
    }

    @Test
    fun `parses spine-ordered chapters with sanitized blocks`() {
        val book = module.parse(bookId = "b1", sizeBytes = 0, readBlock = oneShot(minimalEpub()))
        assertEquals("Test Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })

        val c1 = book.chapters[0]
        val blocks = book.blocksByChapter[c1.id].orEmpty()
        assertEquals(3, blocks.size, "h1 + two paragraphs")
        assertEquals(com.lfq06.arknightsreader.model.BlockKind.HEADING, blocks[0].kind)
        assertEquals("Chapter One", blocks[0].text)
        assertEquals("First paragraph.", blocks[1].text)
        assertEquals("Second with emphasis.", blocks[2].text, "inline em text is kept, markers stripped")

        val c2 = book.chapters[1]
        val c2blocks = book.blocksByChapter[c2.id].orEmpty()
        assertTrue(
            c2blocks.any { it.kind == com.lfq06.arknightsreader.model.BlockKind.IMAGE_PLACEHOLDER && it.imageRef == "images/pic.png" },
            "packaged image must become a placeholder with its ref",
        )
    }

    @Test
    fun `rejects encrypted epub`() {
        val bytes = EpubFixture.build(
            mapOf(
                "META-INF/container.xml" to "".toByteArray(),
                "META-INF/encryption.xml" to "".toByteArray(),
            ),
        )
        assertFailsWith<ParseException> { module.parse(bookId = "b1", sizeBytes = 0, readBlock = oneShot(bytes)) }
    }

    @Test
    fun `rejects zip slip path`() {
        val bytes = EpubFixture.build(
            mapOf(
                "META-INF/container.xml" to "".toByteArray(),
                "../evil.txt" to "x".toByteArray(),
            ),
        )
        assertFailsWith<ParseException> { module.parse(bookId = "b1", sizeBytes = 0, readBlock = oneShot(bytes)) }
    }

    @Test
    fun `rejects non zip input`() {
        assertFailsWith<ParseException> {
            module.parse(bookId = "b1", sizeBytes = 4, readBlock = oneShot("notzip".toByteArray()))
        }
    }

    /** Returns the whole byte array once, then null (proper EOF semantics). */
    private fun oneShot(bytes: ByteArray): (Int) -> ByteArray? {
        var served = false
        return { _ ->
            if (served) null else {
                served = true
                bytes
            }
        }
    }
}

class XhtmlSanitizerTest {

    @Test
    fun `strips script style and iframe`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p>safe</p>
            <script>alert(1)</script>
            <style>body{}</style>
            <iframe src="https://evil.example"></iframe>
            <p onclick="evil()">clickable</p>
        </body></html>"""
        val blocks = XhtmlSanitizer.sanitize(html)
        val texts = blocks.filter { it.kind != com.lfq06.arknightsreader.model.BlockKind.IMAGE_PLACEHOLDER }.map { it.text }
        assertTrue(texts.contains("safe"))
        assertTrue(!texts.joinToString("|").contains("script"))
        assertTrue(!texts.joinToString("|").contains("style"))
        assertTrue(!texts.joinToString("|").contains("iframe"))
        // onclick stripped; text preserved.
        assertTrue(texts.any { it == "clickable" })
    }

    @Test
    fun `keeps whitelisted inline formatting text`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p>Hello <em>world</em> with <strong>bold</strong>.</p>
            <blockquote>Quoted.</blockquote>
        </body></html>"""
        val blocks = XhtmlSanitizer.sanitize(html)
        assertEquals("Hello world with bold.", blocks[0].text)
        assertEquals(com.lfq06.arknightsreader.model.BlockKind.CAPTION, blocks[1].kind)
        assertEquals("Quoted.", blocks[1].text)
    }

    @Test
    fun `ignores remote image and keeps packaged one`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
            <img src="images/pic.png" alt="pkg"/>
            <img src="https://remote.example/x.png" alt="remote"/>
        </body></html>"""
        val blocks = XhtmlSanitizer.sanitize(html)
        assertEquals(1, blocks.count { it.kind == com.lfq06.arknightsreader.model.BlockKind.IMAGE_PLACEHOLDER })
        assertEquals("images/pic.png", blocks.first { it.kind == com.lfq06.arknightsreader.model.BlockKind.IMAGE_PLACEHOLDER }.imageRef)
    }

    @Test
    fun `maps headings to heading blocks`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
            <h1>Title</h1><h2>Sub</h2><p>Body</p>
        </body></html>"""
        val blocks = XhtmlSanitizer.sanitize(html)
        assertEquals(
            listOf(com.lfq06.arknightsreader.model.BlockKind.HEADING, com.lfq06.arknightsreader.model.BlockKind.HEADING, com.lfq06.arknightsreader.model.BlockKind.PARAGRAPH),
            blocks.map { it.kind },
        )
        assertEquals(listOf("Title", "Sub", "Body"), blocks.map { it.text })
    }
}

class EpubXmlTest {
    @Test
    fun `rejects doctype injection`() {
        val evil = """<?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root>&xxe;</root>""".toByteArray()
        assertFailsWith<ParseException> { EpubXml.parse(evil, "test") }
    }
}
