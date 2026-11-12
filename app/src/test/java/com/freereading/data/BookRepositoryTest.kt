package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import com.freereading.epub.BookImportException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the real EPUB parser and disk transaction, not a mock of the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class BookRepositoryTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        File(app.cacheDir, "speech").deleteRecursively()
        repository = BookRepository(app)
    }

    @Test fun importsRealEpubAndRestoresExactProgressAfterRepositoryRecreation() = runBlocking {
        val book = repository.importBook(sampleUri())
        assertEquals("山间来信", book.title)
        val indexed = repository.loadSpeechBook(book.id)
        assertTrue(indexed.sentences.size > 10)
        assertEquals(setOf(0, 1, 2), indexed.sentences.map { it.chapterIndex }.toSet())
        indexed.sentences.forEach { sentence ->
            val locator = Locator.fromJSON(JSONObject(sentence.locatorJson))!!
            assertEquals(sentence.text, locator.text.highlight)
        }
        val repeated = indexed.sentences.filter { it.text == "这是重复的一句。" }
        assertEquals(2, repeated.size)
        assertNotEquals(repeated[0].locatorJson, repeated[1].locatorJson)
        val sentence = repeated[1]
        repository.saveProgress(ReadingProgress(book.id, sentence.locatorJson, sentence.index, 1_278, 123))

        val recreated = BookRepository(app)
        recreated.refresh()
        assertEquals(book.id, recreated.books.value.single().id)
        assertEquals(sentence.index, recreated.progress(book.id).sentenceIndex)
        assertEquals(1_278L, recreated.progress(book.id).audioOffsetMs)
        assertEquals(sentence.locatorJson, recreated.progress(book.id).locatorJson)
    }

    @Test fun duplicateImportAndLateProgressCannotResurrectRemovedBook() = runBlocking {
        val uri = sampleUri()
        val book = repository.importBook(uri)
        assertEquals(book.id, repository.importBook(uri).id)
        assertEquals(1, repository.books.value.size)
        val cache = File(app.cacheDir, "speech/${book.id}/cached.wav")
        cache.parentFile!!.mkdirs()
        cache.writeBytes(byteArrayOf(1))
        repository.removeBook(book.id)
        repository.saveProgress(ReadingProgress(book.id, sentenceIndex = 2))
        repository.refresh()
        assertTrue(repository.books.value.isEmpty())
        assertFalse(repository.bookFile(book.id).parentFile!!.exists())
        assertFalse(cache.parentFile!!.exists())
    }

    @Test fun brokenImportLeavesNoCommittedOrStagingBook() = runBlocking {
        val invalid = File(app.cacheDir, "broken.epub").apply { writeText("not a zip") }
        try {
            repository.importBook(Uri.fromFile(invalid))
            throw AssertionError("Expected EPUB import to fail")
        } catch (_: BookImportException) {
            assertTrue(repository.books.value.isEmpty())
            assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())
        }
    }

    private fun sampleUri(): Uri {
        val file = File(app.cacheDir, "reading-sample.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        return Uri.fromFile(file)
    }
}
