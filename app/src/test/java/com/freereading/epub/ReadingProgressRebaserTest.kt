package com.freereading.epub

import android.app.Application
import com.freereading.data.BookRecord
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReadingProgressRebaserTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bomShiftKeepsTheSameRealSentenceAndItsAudioOffset() = runBlocking {
        withBook(bom = true) { publication, book, html ->
            val saved = oldSentence(book, html, index = 3, paragraph = 2, offset = 1_287)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(4, saved.sentenceIndex)
            assertEquals(3, result.sentenceIndex)
            assertEquals(1_287L, result.audioOffsetMs)
            assertEquals(book.sentences[3].locatorJson, result.locatorJson)
            assertEquals(saved.updatedAt, result.updatedAt)
        }
    }

    @Test fun repeatedParagraphsUseSourcePositionInsteadOfTheCanonicalLookalikeAtTheOldSelector() = runBlocking {
        withBook(bom = true) { publication, book, html ->
            val saved = oldSentence(book, html, index = 2, paragraph = 1, offset = 2_345)
            val oldSelector = locator(saved.locatorJson!!).locations["cssSelector"] as String
            val wrongElement = Jsoup.parse(html.withoutLeadingHtmlBom()).selectFirst(oldSelector)!!
            assertEquals("重复句。", wrongElement.text())
            assertEquals(book.sentences[6].source!!.locator.locations["cssSelector"], oldSelector)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(2, result.sentenceIndex)
            assertEquals(2_345L, result.audioOffsetMs)
            assertNotEquals(book.sentences[6].locatorJson, result.locatorJson)
        }
    }

    @Test fun anAlreadyCorrectBrowserSelectorWithoutTextKeepsItsVisualPageAndDoesNotShift() = runBlocking {
        withBook(bom = true) { publication, book, _ ->
            val visual = JSONObject(book.sentences[6].locatorJson).apply {
                remove("text")
                getJSONObject("locations").put("progression", 0.63).put("totalProgression", 0.31)
            }.toString()
            val saved = ReadingProgress(book.book.id, visual, 7, 0, 901)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(6, result.sentenceIndex)
            assertEquals(visual, result.locatorJson)
            assertEquals(0L, result.audioOffsetMs)
            assertEquals(901L, result.updatedAt)
        }
    }

    @Test fun anAlreadyCorrectBrowserTextSelectorAlsoKeepsTheCorrectRepeatedParagraph() = runBlocking {
        withBook(bom = true) { publication, book, _ ->
            val json = JSONObject(book.sentences[6].locatorJson)
                .put("text", JSONObject().put("highlight", "重复句。"))
            val saved = ReadingProgress(book.book.id, json.toString(), 7, 0, 902)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(6, result.sentenceIndex)
            assertEquals(book.sentences[6].locatorJson, result.locatorJson)
        }
    }

    @Test fun noBomKeepsTheSameSentenceAndOffset() = runBlocking {
        withBook(bom = false) { publication, book, _ ->
            val sentence = book.sentences[2]
            val saved = ReadingProgress(book.book.id, sentence.locatorJson, 2, 4_321, 903)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(saved, result)
        }
    }

    @Test fun anIndexedHeadTitleFallsBackToTheSameChaptersBodyWithoutAnAudioOffset() = runBlocking {
        withBook(bom = true) { publication, book, html ->
            val selector = Jsoup.parse(html).selectFirst("title")!!.cssSelector()
            val json = JSONObject(book.sentences[0].locatorJson).apply {
                getJSONObject("locations").put("cssSelector", selector)
                put("text", JSONObject().put("highlight", "不应朗读的元数据标题")
                    .put("before", "").put("after", ""))
            }
            val saved = ReadingProgress(book.book.id, json.toString(), 0, 9_999, 904)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(0, result.sentenceIndex)
            assertEquals("原创正文标题", locator(result.locatorJson!!).text.highlight)
            assertEquals(0L, result.audioOffsetMs)
            assertEquals(904L, result.updatedAt)
        }
    }

    @Test fun matchingNeverSearchesAnotherChapterForTheSameText() = runBlocking {
        withBook(bom = true) { publication, book, _ ->
            val sentence = book.sentences.last()
            assertEquals(1, sentence.chapterIndex)
            assertEquals("重复句。", sentence.text)
            val saved = ReadingProgress(book.book.id, sentence.locatorJson, 1, 3_210, 905)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(sentence.index, result.sentenceIndex)
            assertEquals(3_210L, result.audioOffsetMs)
            assertEquals(sentence.locatorJson, result.locatorJson)
        }
    }

    @Test fun anOutOfRangeVisualCursorWithoutSentenceEvidenceFallsBackWithinTheSameResource() = runBlocking {
        withBook(bom = true) { publication, book, _ ->
            val chapterStart = book.sentences.first { it.chapterIndex == 1 }
            val json = JSONObject(chapterStart.locatorJson).apply {
                remove("text")
                put("locations", JSONObject().put("progression", 0.72))
            }.toString()
            val saved = ReadingProgress(book.book.id, json, 999_999, 888, 906)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(chapterStart.index, result.sentenceIndex)
            assertEquals(json, result.locatorJson)
            assertEquals(0L, result.audioOffsetMs)
        }
    }

    @Test fun aNormalVisualPageWithoutASelectorRetainsItsIndependentSpeechSentence() = runBlocking {
        withBook(bom = false) { publication, book, _ ->
            val sentence = book.sentences[6]
            val visual = JSONObject(sentence.locatorJson).apply {
                remove("text")
                put("locations", JSONObject().put("progression", 0.72))
            }.toString()
            val saved = ReadingProgress(book.book.id, visual, sentence.index, 0, 910)

            val result = ReadingProgressRebaser.rebase(publication, book, saved, sentence)

            assertEquals(saved, result)
        }
    }

    @Test fun aVisualPageKeepsItsPositionWhileItsOldSpeechAnchorIsRebasedPastTheBomTitle() = runBlocking {
        withBook(bom = true) { publication, book, html ->
            val old = oldSentence(book, html, index = 2, paragraph = 1, offset = 0)
            val visual = JSONObject(book.sentences[2].locatorJson).apply {
                remove("text")
                put("locations", JSONObject().put("progression", 0.42))
            }.toString()
            val saved = old.copy(locatorJson = visual)
            val evidence = SpeechSentence(old.sentenceIndex, "重复句。", old.locatorJson!!, 0, "第一章")

            val result = ReadingProgressRebaser.rebase(publication, book, saved, evidence)

            assertEquals(2, result.sentenceIndex)
            assertEquals(visual, result.locatorJson)
            assertEquals(0L, result.audioOffsetMs)
            assertEquals(saved.updatedAt, result.updatedAt)
        }
    }

    @Test fun visualPagesRejectEvidenceFromADifferentSentenceOrResource() = runBlocking {
        withBook(bom = false) { publication, book, _ ->
            val sentence = book.sentences[6]
            val visual = JSONObject(sentence.locatorJson).apply {
                remove("text")
                put("locations", JSONObject().put("progression", 0.72))
            }.toString()
            val saved = ReadingProgress(book.book.id, visual, 6, 999, 911)
            val anotherChapter = book.sentences.last()
            val wrongHref = SpeechSentence(6, anotherChapter.text, anotherChapter.locatorJson, 0, "错误资源")
            for (evidence in listOf(book.sentences[5], wrongHref)) {
                val result = ReadingProgressRebaser.rebase(publication, book, saved, evidence)
                assertEquals(0, result.sentenceIndex)
                assertEquals(visual, result.locatorJson)
                assertEquals(0L, result.audioOffsetMs)
            }
        }
    }

    @Test fun malformedOrMissingLocatorsClampSafelyAndNeverKeepAnUnverifiedOffset() = runBlocking {
        withBook(bom = true) { publication, book, _ ->
            for ((json, index) in listOf("not-json" to Int.MAX_VALUE, null to -99, "{}" to 999)) {
                val saved = ReadingProgress(book.book.id, json, index, 7_777, 907)

                val result = ReadingProgressRebaser.rebase(publication, book, saved)

                assertEquals(index.coerceIn(book.sentences.indices), result.sentenceIndex)
                assertEquals(book.sentences[result.sentenceIndex].locatorJson, result.locatorJson)
                assertEquals(0L, result.audioOffsetMs)
                assertEquals(907L, result.updatedAt)
            }
        }
    }

    @Test fun anAfterContextOutsideTheSourceRangeCannotCarryAnUnverifiedAudioOffset() = runBlocking {
        withBook(bom = false) { publication, book, _ ->
            val json = JSONObject(book.sentences[3].locatorJson).apply {
                getJSONObject("text").put("after", "这段上下文并不在原段落范围内。")
            }
            val saved = ReadingProgress(book.book.id, json.toString(), Int.MAX_VALUE, 8_888, 908)

            val result = ReadingProgressRebaser.rebase(publication, book, saved)

            assertEquals(3, result.sentenceIndex)
            assertEquals(book.sentences[3].locatorJson, result.locatorJson)
            assertEquals(0L, result.audioOffsetMs)
            assertEquals(908L, result.updatedAt)
        }
    }

    private fun oldSentence(book: SpeechBook, html: String, index: Int, paragraph: Int, offset: Long): ReadingProgress {
        val json = JSONObject(book.sentences[index].locatorJson)
        val oldSelector = Jsoup.parse(html).select("p.a")[paragraph].cssSelector()
        assertNotEquals(json.getJSONObject("locations").getString("cssSelector"), oldSelector)
        json.getJSONObject("locations").put("cssSelector", oldSelector)
        // Old content locators include context fields (also when the after-context is empty).
        val text = json.getJSONObject("text")
        text.put("before", "不应朗读的元数据标题" + text.optString("before"))
        text.put("after", text.optString("after"))
        return ReadingProgress(book.book.id, json.toString(), index + 1, offset, 900)
    }

    private suspend fun withBook(bom: Boolean, action: suspend (Publication, SpeechBook, String) -> Unit) {
        val html = (if (bom) "\uFEFF" else "") + """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head>
            <meta charset="UTF-8"/><meta name="generator" content="原创回归"/>
            <title>不应朗读的元数据标题</title><link rel="stylesheet" href="style.css"/>
            </head><body><h1>原创正文标题</h1><p class="a">重复句。</p><p class="a">重复句。</p>
            <p class="a">第三句。</p><p class="a">重复句。</p><p class="a">第五句。</p>
            <p class="a">重复句。</p><p class="a">末句。</p></body></html>""".trimIndent()
        val file = fixture(html)
        val publication = ReadiumEngine(RuntimeEnvironment.getApplication()).open(file)
        try {
            val sentences = SentenceIndexer().index(publication)
            assertEquals(10, sentences.size)
            assertTrue(sentences.none { it.text == "不应朗读的元数据标题" })
            val book = SpeechBook(BookRecord("a".repeat(64), "原创迁移测试", "原创作者", "zh", 0, sentences.size), sentences)
            action(publication, book, html)
        } finally { publication.close() }
    }

    private fun fixture(html: String): File {
        val file = File(temporary.root, "original-rebase.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.text("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            zip.text("EPUB/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:freereading:original-rebase</dc:identifier>
                <dc:title>原创迁移测试</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">2026-11-16T00:00:00Z</meta></metadata>
                <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="style" href="style.css" media-type="text/css"/></manifest>
                <spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            zip.text("EPUB/one.xhtml", html)
            zip.text("EPUB/two.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head>
                <body><h1>第二章正文</h1><p class="a">重复句。</p></body></html>""")
            zip.text("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">第一章</a></li>
                <li><a href="two.xhtml">第二章</a></li></ol></nav></body></html>""")
            zip.text("EPUB/style.css", "p { color: #222; }")
        }
        return file
    }

    private fun locator(json: String): Locator = requireNotNull(Locator.fromJSON(JSONObject(json)))
    private fun ZipOutputStream.text(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
