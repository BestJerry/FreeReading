package com.freereading.epub

import android.app.Application
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.use
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An original EPUB reproduces the BOM/head relocation observed with Jsoup and a real content service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BomAwareHtmlContentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun leadingUtf8BomCannotIndexHeadTextOrShiftRepeatedParagraphSelectors() = runBlocking {
        val file = fixture("leading-bom.epub", bom = true)
        val originalBytes = file.readBytes()
        val publication = ReadiumEngine(RuntimeEnvironment.getApplication()).open(file)
        try {
            val sentences = SentenceIndexer().index(publication)
            assertEquals(listOf("原创正文标题", "山间有回声。", "山间有回声。", "最后一句。"), sentences.map { it.text })
            val locators = sentences.map { requireNotNull(Locator.fromJSON(JSONObject(it.locatorJson))) }
            assertEquals(listOf("html > body > h1", "html > body > p.a:nth-child(2)",
                "html > body > p.a:nth-child(3)", "html > body > p.a:nth-child(4)"),
                locators.map { it.locations["cssSelector"] })
            assertTrue(locators.none { (it.locations["cssSelector"] as String).contains("title") })
            assertNotEquals(sentences[1].locatorJson, sentences[2].locatorJson)
            locators.forEachIndexed { index, locator -> assertEquals(sentences[index].text, locator.text.highlight) }

            // The content iterator owns the normalization. Navigator reads still see the original resource bytes.
            val resource = requireNotNull(publication.get(publication.readingOrder.single()))
            resource.use {
                val raw = it.read().getOrElse { error -> throw AssertionError("Fixture resource unreadable: $error") }
                assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), raw.copyOfRange(0, 3))
            }
            assertArrayEquals(originalBytes, file.readBytes())
        } finally { publication.close() }
    }

    @Test fun noBomHtmlKeepsTheSameBodyTextAndBrowserParagraphPositions() = runBlocking {
        val publication = ReadiumEngine(RuntimeEnvironment.getApplication()).open(fixture("no-bom.epub", bom = false))
        try {
            val sentences = SentenceIndexer().index(publication)
            assertEquals(listOf("原创正文标题", "山间有回声。", "山间有回声。", "最后一句。"), sentences.map { it.text })
            assertEquals("html > body > p.a:nth-child(3)",
                Locator.fromJSON(JSONObject(sentences[2].locatorJson))!!.locations["cssSelector"])
        } finally { publication.close() }
    }

    @Test fun interiorFeffRemainsInSpokenTextAndItsLocatorWithOrWithoutAFileBom() = runBlocking {
        for (bom in listOf(false, true)) {
            val publication = ReadiumEngine(RuntimeEnvironment.getApplication()).open(
                fixture("interior-$bom.epub", bom, interiorFeff = true))
            try {
                val sentences = SentenceIndexer().index(publication)
                assertEquals(4, sentences.size)
                val sentence = sentences.last()
                assertEquals("前文\uFEFF后文。", sentence.text)
                val locator = Locator.fromJSON(JSONObject(sentence.locatorJson))!!
                assertEquals(sentence.text, locator.text.highlight)
                assertFalse(sentence.text.startsWith('\uFEFF'))
                assertTrue(sentence.text.contains('\uFEFF'))
            } finally { publication.close() }
        }
    }

    private fun fixture(name: String, bom: Boolean, interiorFeff: Boolean = false): File {
        val target = File(temporary.root, name)
        ZipOutputStream(target.outputStream()).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.text("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles>
                <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
                </rootfiles></container>""".trimIndent())
            zip.text("EPUB/package.opf", """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book">urn:freereading:bom-test</dc:identifier>
                <dc:title>原创 BOM 定位回归</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">2026-11-16T00:00:00Z</meta></metadata>
                <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="style" href="style.css" media-type="text/css"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>""".trimIndent())
            zip.text("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>原创目录</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">原创章节</a></li></ol></nav></body></html>""".trimIndent())
            zip.text("EPUB/style.css", "body { color: #222; }")
            val ending = if (interiorFeff) "前文\uFEFF后文。" else "最后一句。"
            val html = """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head>
                <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
                <meta name="generator" content="FreeReading original test"/>
                <title>不该朗读的元数据标题</title><link rel="stylesheet" href="style.css" type="text/css"/>
                </head><body><h1>原创正文标题</h1><p class="a">山间有回声。</p><p class="a">山间有回声。</p><p class="a">$ending</p></body></html>""".trimIndent()
            zip.text("EPUB/chapter.xhtml", (if (bom) "\uFEFF" else "") + html)
        }
        return target
    }

    private fun ZipOutputStream.text(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
