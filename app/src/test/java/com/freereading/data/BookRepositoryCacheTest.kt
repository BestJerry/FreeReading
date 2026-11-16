package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class BookRepositoryCacheTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        repository = BookRepository(app)
    }

    @After fun clean() { File(app.filesDir, "books").deleteRecursively() }

    @Test fun liveReadersAndPlayersShareTheSameBookWhileNewRepositoriesReadTheDisk() = runBlocking {
        val record = importSample()
        val reading = repository.loadSpeechBook(record.id)
        val listening = repository.loadSpeechBook(record.id)
        assertSame(reading, listening)
        val reopened = BookRepository(app).loadSpeechBook(record.id)
        assertNotSame(reading, reopened)
        assertEquals(reading.sentences.first().locatorJson, reopened.sentences.first().locatorJson)
        assertEquals(1, JSONObject(File(repository.bookFile(record.id).parentFile, "book.json").readText()).getInt("version"))
    }

    @Test fun changedIndexLengthCannotBeHiddenByALiveCachedBook() = runBlocking {
        val record = importSample()
        val reading = repository.loadSpeechBook(record.id)
        val file = File(repository.bookFile(record.id).parentFile, "speech.json")
        file.appendText("invalid trailing bytes")
        expectIo { repository.loadSpeechBook(record.id) }
        assertTrue(reading.sentences.isNotEmpty())
    }

    @Test fun changedIndexTimestampForcesAReloadEvenWithTheSameLength() = runBlocking {
        val record = importSample()
        val reading = repository.loadSpeechBook(record.id)
        val file = File(repository.bookFile(record.id).parentFile, "speech.json")
        assertTrue(file.setLastModified(file.lastModified() + 2_000))
        val reloaded = repository.loadSpeechBook(record.id)
        assertNotSame(reading, reloaded)
        assertEquals(reading.sentences.first().text, reloaded.sentences.first().text)
    }

    @Test fun changedMetadataCannotReturnAnOutdatedBookRecord() = runBlocking {
        val record = importSample()
        val reading = repository.loadSpeechBook(record.id)
        val file = File(repository.bookFile(record.id).parentFile, "book.json")
        val modified = file.lastModified()
        val json = JSONObject(file.readText()).put("title", "林间回声")
        file.writeText(json.toString())
        file.setLastModified(modified)
        val reloaded = repository.loadSpeechBook(record.id)
        assertNotSame(reading, reloaded)
        assertEquals("林间回声", reloaded.book.title)
    }

    @Test fun missingFilesAndRemovedBooksCannotBeRestoredFromTheCache() = runBlocking {
        val record = importSample()
        val reading = repository.loadSpeechBook(record.id)
        assertTrue(File(repository.bookFile(record.id).parentFile, "speech.json").delete())
        expectIo { repository.loadSpeechBook(record.id) }
        repository.removeBook(record.id)
        expectIo { repository.loadSpeechBook(record.id) }
        assertTrue(reading.sentences.isNotEmpty())
    }

    private suspend fun importSample(): BookRecord {
        val file = File(app.cacheDir, "cache-regression.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return repository.importBook(Uri.fromFile(file))
    }

    private suspend inline fun expectIo(action: () -> Unit) {
        try {
            action()
            fail("Expected the changed or missing book to fail")
        } catch (_: IOException) { }
    }
}
