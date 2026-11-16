package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regress former application limits using original EPUBs and the real parser plus persisted index. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class BookRepositoryCapacityTest {
    private lateinit var app: ReadingApplication
    private lateinit var fixtures: File
    private lateinit var repository: BookRepository

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        fixtures = File(app.cacheDir, "original-capacity-epubs").apply { mkdirs() }
        repository = BookRepository(app)
    }

    @After fun cleanGeneratedBooks() {
        File(app.filesDir, "books").deleteRecursively()
        fixtures.deleteRecursively()
    }

    @Test fun eightThousandCharactersOfRepeatedTextImportAndReopenWithFullUniqueLocators() = runBlocking {
        val source = OriginalEpubFixture.repeatedParagraph(File(fixtures, "repeated-block.epub"))
        val record = repository.importBook(Uri.fromFile(source))
        assertEquals(4_000, record.sentenceCount)

        val recreated = BookRepository(app)
        recreated.refresh()
        assertEquals(record.id, recreated.books.value.single().id)
        val reopened = recreated.loadSpeechBook(record.id)
        assertEquals(4_000, reopened.sentences.size)
        assertTrue(reopened.sentences.all { it.text == "甲。" })
        // Keep only fixed-size digests: locatorJson can be reconstructed lazily from a shared source.
        val digest = MessageDigest.getInstance("SHA-256")
        val distinctLocators = HashSet<String>()
        var expandedCharacters = 0L
        for (sentence in reopened.sentences) {
            val locator = sentence.locatorJson
            expandedCharacters += locator.length
            distinctLocators += Base64.getEncoder().encodeToString(digest.digest(locator.toByteArray(Charsets.UTF_8)))
        }
        assertEquals(4_000, distinctLocators.size)
        assertTrue(expandedCharacters > 32_000_000L)
        val storedIndex = File(recreated.bookFile(record.id).parentFile, "speech.json")
        assertTrue(storedIndex.isFile)
        assertTrue("The index must store the paragraph once, not duplicate every full locator", storedIndex.length() < 1_000_000L)

        // Only materialize a few selectors' expected contexts, never another full expanded index.
        val fullParagraph = "甲。".repeat(4_000)
        for (index in listOf(0, 1, 1_999, 2_000, 3_999)) {
            val sentence = reopened.sentences[index]
            val locator = requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson)))
            assertEquals("甲。", locator.text.highlight)
            assertEquals(index * 2, locator.text.before.orEmpty().length)
            assertEquals((3_999 - index) * 2, locator.text.after.orEmpty().length)
            assertEquals(fullParagraph, locator.text.before.orEmpty() + locator.text.highlight.orEmpty() + locator.text.after.orEmpty())
        }
        assertNotEquals(reopened.sentences[1].locatorJson, reopened.sentences[2].locatorJson)
    }

    @Test fun moreThanOneHundredThousandSentencesImportAndReopenAcrossChapters() = runBlocking {
        val source = OriginalEpubFixture.numberedChapters(File(fixtures, "original-long-novel.epub"))
        val record = repository.importBook(Uri.fromFile(source))
        assertEquals(100_001, record.sentenceCount)

        // No in-memory import result is reused: metadata and all sentence selectors come from disk.
        val recreated = BookRepository(app)
        recreated.refresh()
        assertEquals(100_001, recreated.books.value.single().sentenceCount)
        val reopened = recreated.loadSpeechBook(record.id)
        assertEquals(100_001, reopened.sentences.size)
        assertEquals("第1句，溪水静静流过山谷。", reopened.sentences.first().text)
        assertEquals("第100001句，溪水静静流过山谷。", reopened.sentences.last().text)
        assertTrue(reopened.sentences.sumOf { it.text.length.toLong() } in 1_000_000L..3_000_000L)
        assertEquals(0, reopened.sentences[999].chapterIndex)
        assertEquals(1, reopened.sentences[1_000].chapterIndex)
        assertEquals(100, reopened.sentences.last().chapterIndex)

        for (index in listOf(0, 19, 20, 999, 1_000, 50_000, 100_000)) {
            val sentence = reopened.sentences[index]
            assertEquals(index, sentence.index)
            val locator = requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson)))
            assertEquals(sentence.text, locator.text.highlight)
        }
    }

    @Test fun moreThanEightMillionBodyCharactersImportAndReopenWithoutLongParagraphs() = runBlocking {
        val source = OriginalEpubFixture.longBody(File(fixtures, "original-large-body.epub"))
        val record = repository.importBook(Uri.fromFile(source))
        assertEquals(16_001, record.sentenceCount)

        val recreated = BookRepository(app)
        recreated.refresh()
        assertEquals(record.id, recreated.books.value.single().id)
        val reopened = recreated.loadSpeechBook(record.id)
        assertEquals(16_001, reopened.sentences.size)
        assertTrue(reopened.sentences.all { it.text.length == 500 })
        assertEquals(8_000_500L, reopened.sentences.sumOf { it.text.length.toLong() })
        assertEquals(0, reopened.sentences[199].chapterIndex)
        assertEquals(1, reopened.sentences[200].chapterIndex)
        assertEquals(80, reopened.sentences.last().chapterIndex)

        for (index in listOf(0, 9, 10, 199, 200, 16_000)) {
            val sentence = reopened.sentences[index]
            val locator = requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson)))
            assertEquals(sentence.text, locator.text.highlight)
        }
    }

    @Test fun paragraphAt65536CharacterBoundaryImportsAndReopensWithFullContexts() = runBlocking {
        val source = OriginalEpubFixture.repeatedParagraph(File(fixtures, "maximum-short-sentence-block.epub"),
            repetitions = 32_768)
        val record = repository.importBook(Uri.fromFile(source))
        assertEquals(32_768, record.sentenceCount)

        val recreated = BookRepository(app)
        recreated.refresh()
        assertEquals(record.id, recreated.books.value.single().id)
        val reopened = recreated.loadSpeechBook(record.id)
        assertEquals(32_768, reopened.sentences.size)
        assertTrue(reopened.sentences.all { it.text == "甲。" })
        val fullParagraph = "甲。".repeat(32_768)
        // Expanding every locator would itself allocate gigabytes; inspect representative boundaries only.
        for (index in listOf(0, 1, 16_384, 32_767)) {
            val locator = requireNotNull(Locator.fromJSON(JSONObject(reopened.sentences[index].locatorJson)))
            assertEquals("甲。", locator.text.highlight)
            assertEquals(index * 2, locator.text.before.orEmpty().length)
            assertEquals((32_767 - index) * 2, locator.text.after.orEmpty().length)
            assertEquals(fullParagraph, locator.text.before.orEmpty() + locator.text.highlight.orEmpty() + locator.text.after.orEmpty())
        }
    }
}
