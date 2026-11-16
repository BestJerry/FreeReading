package com.freereading.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechIndexStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = SpeechIndexStore()
    private val id = "a".repeat(64)

    @Test fun repeatedSentencesPersistOneSourceAndRestoreTheirFullDistinctContexts() = runBlocking {
        val source = source("甲。".repeat(100))
        val original = book(List(100) { SpeechSentence(it, source, it * 2, it * 2 + 2) })
        val file = target()
        store.write(file, original)

        val json = JSONObject(file.readText())
        assertEquals(2, json.getInt("version"))
        assertEquals(1, json.getJSONArray("sources").length())
        val entry = json.getJSONArray("sentences").getJSONObject(73)
        assertFalse(entry.has("text"))
        assertFalse(entry.has("locatorJson"))

        val loaded = store.read(file, id, 100)
        assertSame(loaded[0].source, loaded[73].source)
        assertEquals(source.text, loaded[73].source!!.text)
        assertEquals("甲。", loaded[73].text)
        val locator = Locator.fromJSON(JSONObject(loaded[73].locatorJson))!!
        assertEquals("前段" + "甲。".repeat(73), locator.text.before)
        assertEquals("甲。".repeat(26) + "后段", locator.text.after)
        assertEquals(original.sentences[73].locatorJson, loaded[73].locatorJson)
        assertNotEquals(loaded[72].locatorJson, loaded[73].locatorJson)
    }

    @Test fun v1IndexesRetainTheirOriginalTextAndLocatorWithoutMigration() = runBlocking {
        val source = source("旧句。")
        val locatorJson = source.locator.toJSON().toString()
        val legacy = JSONObject().put("version", 1).put("bookId", id).put("sentences", JSONArray().put(
            JSONObject().put("index", 0).put("text", "旧句。").put("locatorJson", locatorJson)
                .put("chapterIndex", 2).put("chapterTitle", "旧章"),
        ))
        val file = target().apply { writeText(legacy.toString()) }

        val loaded = store.read(file, id, 1).single()

        assertNull(loaded.source)
        assertEquals("旧句。", loaded.text)
        assertEquals(locatorJson, loaded.locatorJson)
        assertEquals(2, loaded.chapterIndex)
        assertEquals("旧章", loaded.chapterTitle)
        assertEquals(1, JSONObject(file.readText()).getInt("version"))
    }

    @Test fun oldFiveArgumentConstructionDoesNotParseOpaqueLocators() {
        val sentence = SpeechSentence(7, "测试。", "opaque-test-locator", 2, "章节")
        assertNull(sentence.source)
        assertEquals("测试。", sentence.text)
        assertEquals("opaque-test-locator", sentence.locatorJson)
        assertEquals(2, sentence.chapterIndex)
    }

    @Test fun v2FieldsCanAppearBeforeTheSourceTableOrVersion() = runBlocking {
        val file = target()
        store.write(file, oneSentenceBook())
        val original = JSONObject(file.readText())
        val reordered = JSONObject().put("sentences", original.getJSONArray("sentences"))
            .put("bookId", id).put("sources", original.getJSONArray("sources")).put("version", 2)
        file.writeText(reordered.toString())

        assertEquals("第一句。", store.read(file, id, 1).single().text)
    }

    @Test fun exactByteBoundarySucceedsAndOversizedReplacementRestoresThePreviousFile() = runBlocking {
        val file = target()
        val original = oneSentenceBook()
        store.write(file, original)
        val bytes = file.readBytes()
        SpeechIndexStore(bytes.size.toLong()).write(file, original)
        assertEquals("第一句。", SpeechIndexStore(bytes.size.toLong()).read(file, id, 1).single().text)

        expectIo { SpeechIndexStore(bytes.size.toLong() - 1).write(file, original) }

        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun oversizedFirstWriteLeavesNoIndexOrAtomicTemporaryFile() = runBlocking {
        val file = target()
        expectIo { SpeechIndexStore(100).write(file, oneSentenceBook()) }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun readAppliesTheSameByteLimitAsWrite() = runBlocking {
        val file = target()
        store.write(file, oneSentenceBook())
        expectIo { SpeechIndexStore(file.length() - 1).read(file, id, 1) }
    }

    @Test fun invalidReferencesAndUtf16RangesAreRejected() = runBlocking {
        val source = source("甲😀乙。")
        val file = target()
        store.write(file, book(listOf(SpeechSentence(0, source, 0, source.text.length))))
        val valid = file.readText()
        for ((field, value) in listOf("sourceIndex" to -1, "sourceIndex" to 1,
            "startOffset" to -1, "endOffset" to 99, "endOffset" to 0,
            "startOffset" to 2, "endOffset" to 2)) {
            val corrupt = JSONObject(valid)
            corrupt.getJSONArray("sentences").getJSONObject(0).put(field, value)
            file.writeText(corrupt.toString())
            expectIo { store.read(file, id, 1) }
        }
    }

    @Test fun metadataMismatchUnsupportedVersionAndIncompleteIndexesAreRejected() = runBlocking {
        val file = target()
        store.write(file, oneSentenceBook())
        val valid = file.readText()
        expectIo { store.read(file, "b".repeat(64), 1) }
        expectIo { store.read(file, id, 2) }
        file.writeText(JSONObject(valid).put("version", 99).toString())
        expectIo { store.read(file, id, 1) }
        file.writeText(JSONObject(valid).put("sentences", JSONArray()).toString())
        expectIo { store.read(file, id, 1) }
        expectIo { store.read(file, id, SpeechIndexLimits.MAX_SENTENCES + 1) }
    }

    @Test fun oversizedSourceLocatorIsRejectedBeforeItsJsonIsParsed() = runBlocking {
        val file = target()
        store.write(file, oneSentenceBook())
        val corrupt = JSONObject(file.readText())
        corrupt.getJSONArray("sources").getJSONObject(0).put("locatorJson", "x".repeat(131_073))
        file.writeText(corrupt.toString())
        expectIo { store.read(file, id, 1) }
    }

    @Test fun utf16RangeCanIncludeAnEntireEmoji() = runBlocking {
        val source = source("甲😀乙。")
        val file = target()
        store.write(file, book(listOf(SpeechSentence(0, source, 1, 3))))
        val loaded = store.read(file, id, 1).single()
        assertEquals("😀", loaded.text)
        assertEquals("前段甲", Locator.fromJSON(JSONObject(loaded.locatorJson))!!.text.before)
        assertTrue(loaded.locatorJson.contains("😀"))
    }

    @Test fun singleSentenceReadSupportsV1AndPreservesTheExactLocator() = runBlocking {
        val entries = JSONArray()
        repeat(3) { index ->
            val text = "旧版第${index}句。"
            entries.put(JSONObject().put("index", index).put("text", text)
                .put("locatorJson", source(text).locator.toJSON().toString())
                .put("chapterIndex", 2).put("chapterTitle", "旧章"))
        }
        val file = target().apply {
            writeText(JSONObject().put("version", 1).put("bookId", id).put("sentences", entries).toString())
        }

        val sentence = store.readSentence(file, id, 3, 1)

        assertEquals(1, sentence.index)
        assertEquals("旧版第1句。", sentence.text)
        assertEquals(entries.getJSONObject(1).getString("locatorJson"), sentence.locatorJson)
        assertNull(sentence.source)
        assertEquals(2, sentence.chapterIndex)
    }

    @Test fun singleSentenceReadSupportsV2RangesAndSourcesAfterTheSentences() = runBlocking {
        val first = source("甲。".repeat(100))
        val second = source("另一段。")
        val sentences = List(100) { SpeechSentence(it, first, it * 2, it * 2 + 2) } +
            SpeechSentence(100, second, 0, second.text.length)
        val file = target()
        store.write(file, book(sentences))
        val json = JSONObject(file.readText())
        file.writeText(JSONObject().put("sentences", json.getJSONArray("sentences"))
            .put("sources", json.getJSONArray("sources")).put("version", 2).put("bookId", id).toString())

        for (index in listOf(73, 100)) {
            val sentence = store.readSentence(file, id, sentences.size, index)
            assertEquals(index, sentence.index)
            assertEquals(sentences[index].text, sentence.text)
            assertEquals(sentences[index].locatorJson, sentence.locatorJson)
        }
    }

    @Test fun singleSentenceReadRejectsMismatchedCountsAndInvalidEvidence() = runBlocking {
        val file = target()
        val emoji = source("甲😀乙。")
        store.write(file, book(listOf(SpeechSentence(0, emoji, 0, emoji.text.length))))
        val valid = file.readText()
        expectIo { store.readSentence(file, id, 2, 0) }
        expectIo { store.readSentence(file, "b".repeat(64), 1, 0) }
        expectIo { store.readSentence(file, id, 1, 1) }
        expectIo { SpeechIndexStore(file.length() - 1).readSentence(file, id, 1, 0) }
        for ((field, value) in listOf("sourceIndex" to 1, "endOffset" to 99,
            "startOffset" to 2, "endOffset" to 2)) {
            val corrupt = JSONObject(valid)
            corrupt.getJSONArray("sentences").getJSONObject(0).put(field, value)
            file.writeText(corrupt.toString())
            expectIo { store.readSentence(file, id, 1, 0) }
        }
        file.writeText(JSONObject(valid).put("version", 99).toString())
        expectIo { store.readSentence(file, id, 1, 0) }
        file.writeText(valid + "{}")
        expectIo { store.readSentence(file, id, 1, 0) }
        file.writeText(valid.dropLast(1) + ",\"bookId\":\"$id\"}")
        expectIo { store.readSentence(file, id, 1, 0) }
    }

    @Test fun singleSentenceReadStreamsALargeSourceTableInsteadOfLoadingTheOldBook() = runBlocking {
        val file = target()
        val count = 10_000
        val locator = source("溪水".repeat(2_000)).locator.toJSON().toString()
        file.bufferedWriter().use { writer ->
            writer.write("{\"version\":2,\"bookId\":\"$id\",\"sources\":[")
            val entry = JSONObject().put("locatorJson", locator).put("chapterIndex", 0)
                .put("chapterTitle", "原创大索引").toString()
            repeat(count) { index ->
                if (index > 0) writer.write(",")
                writer.write(entry)
            }
            writer.write("],\"sentences\":[")
            repeat(count) { index ->
                if (index > 0) writer.write(",")
                writer.write("{\"index\":$index,\"sourceIndex\":$index,\"startOffset\":0,\"endOffset\":2}")
            }
            writer.write("]}")
        }

        val sentence = store.readSentence(file, id, count, count - 2)

        assertTrue(file.length() > 40_000_000L)
        assertEquals(count - 2, sentence.index)
        assertEquals("溪水", sentence.text)
        assertEquals(4_000, sentence.source!!.text.length)
    }

    private fun oneSentenceBook(): SpeechBook {
        val source = source("第一句。")
        return book(listOf(SpeechSentence(0, source, 0, source.text.length)))
    }

    private fun source(text: String): SpeechSource {
        val json = JSONObject().put("href", "chapter.xhtml").put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("cssSelector", "#paragraph"))
            .put("text", JSONObject().put("before", "前段").put("highlight", text).put("after", "后段"))
        return SpeechSource(Locator.fromJSON(json)!!, 0, "原创章节")
    }

    private fun book(sentences: List<SpeechSentence>) = SpeechBook(
        BookRecord(id, "原创测试书", "作者", "zh", 0, sentences.size), sentences,
    )

    private fun target() = File(temporary.root, "speech.json")

    private suspend inline fun expectIo(action: () -> Unit) {
        try {
            action()
            fail("Expected an invalid or oversized index to fail")
        } catch (_: IOException) { }
    }
}
