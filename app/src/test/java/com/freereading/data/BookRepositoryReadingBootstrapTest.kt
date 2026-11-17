package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class BookRepositoryReadingBootstrapTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository
    private lateinit var fixture: File

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        repository = BookRepository(app)
        fixture = File(app.cacheDir, "reading-bootstrap-original.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
    }

    @After fun clean() {
        File(app.filesDir, "books").deleteRecursively()
        fixture.delete()
    }

    @Test fun aColdRepositoryPreparesMetadataAndTheExactSavedCursorWithoutAFullBook() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        val saved = ReadingProgress(record.id, full.sentences[2].locatorJson, 2, 4_321, 123)
        repository.saveProgress(saved)
        val reopened = BookRepository(app)

        val bootstrap = reopened.prepareReadingBook(record.id)

        assertEquals(record, bootstrap.record)
        assertEquals(saved, bootstrap.progress)
        assertNull(bootstrap.cachedSpeechBook)
        assertEquals(emptyList<BookRecord>(), reopened.books.value) // No shelf refresh prerequisite.
    }

    @Test fun preparingMetadataDoesNotParseOrCertifyTheFullSpeechJson() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        index(record).writeText("The full index will be checked when speech is requested.")
        val reopened = BookRepository(app)

        val bootstrap = reopened.prepareReadingBook(record.id)

        assertEquals(record, bootstrap.record)
        assertNull(bootstrap.cachedSpeechBook)
        expectIo { reopened.loadSpeechBook(record.id) }
    }

    @Test fun aLiveUnchangedFullBookIsReturnedWithoutDuplicatingItsSentenceObjects() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)

        val bootstrap = repository.prepareReadingBook(record.id)

        assertSame(full, bootstrap.cachedSpeechBook)
        assertSame(full.sentences, bootstrap.cachedSpeechBook!!.sentences)
    }

    @Test fun changedIndexStampInvalidatesTheOptionalWarmBook() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        index(record).appendText(" ")

        val bootstrap = repository.prepareReadingBook(record.id)

        assertNull(bootstrap.cachedSpeechBook)
        assertEquals(full.book, bootstrap.record)
        assertEquals(full.sentences.size, repository.loadSpeechBook(record.id).sentences.size)
    }

    @Test fun sameLengthIndexTimestampChangesAlsoInvalidateTheOptionalWarmBook() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        assertTrue(index(record).setLastModified(index(record).lastModified() + 2_000))

        assertNull(repository.prepareReadingBook(record.id).cachedSpeechBook)
        assertTrue(full.sentences.isNotEmpty())
    }

    @Test fun changedMetadataIsReflectedAndCannotReturnAnOutdatedWarmRecord() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        val metadata = file(record, "book.json")
        metadata.writeText(JSONObject(metadata.readText()).put("title", "溪谷新标题").toString())

        val bootstrap = repository.prepareReadingBook(record.id)

        assertEquals("溪谷新标题", bootstrap.record.title)
        assertNull(bootstrap.cachedSpeechBook)
        assertEquals(record.title, full.book.title)
    }

    @Test fun atomicIndexBackupIsRecoveredWithoutParsingItsBody() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val original = index(record).readBytes()
        val backup = File(index(record).path + ".bak")
        assertTrue(index(record).renameTo(backup))
        val reopened = BookRepository(app)

        val bootstrap = reopened.prepareReadingBook(record.id)

        assertEquals(record, bootstrap.record)
        assertNull(bootstrap.cachedSpeechBook)
        assertTrue(index(record).isFile)
        assertFalse(backup.exists())
        assertTrue(original.contentEquals(index(record).readBytes()))
        assertEquals(record.sentenceCount, reopened.loadSpeechBook(record.id).sentences.size)
    }

    @Test fun missingIndexIsNotHiddenByALiveFullBook() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        assertTrue(index(record).delete())

        expectIo { repository.prepareReadingBook(record.id) }
        assertFalse(index(record).exists())
        assertTrue(full.sentences.isNotEmpty())
    }

    @Test fun emptyAndOversizedIndexesAreRejectedWithoutReadingTheirContents() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        index(record).writeBytes(byteArrayOf())
        expectIo { repository.prepareReadingBook(record.id) }
        RandomAccessFile(index(record), "rw").use { it.setLength(SpeechIndexLimits.MAX_INDEX_BYTES + 1) }
        expectIo { repository.prepareReadingBook(record.id) }
        assertTrue(full.sentences.isNotEmpty())
    }

    @Test fun missingOriginalAndRemovedBooksCannotBeRestoredFromTheWarmCache() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        assertTrue(repository.bookFile(record.id).delete())
        expectIo { repository.prepareReadingBook(record.id) }
        repository.removeBook(record.id)
        expectIo { repository.prepareReadingBook(record.id) }
        assertFalse(repository.bookFile(record.id).parentFile!!.exists())
        assertTrue(full.sentences.isNotEmpty())
    }

    @Test fun futureMetadataVersionsAreRejectedEvenWhenTheFullBookWasCached() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        val metadata = file(record, "book.json")
        metadata.writeText(JSONObject(metadata.readText()).put("textIndexVersion", 99).toString())

        expectIo { repository.prepareReadingBook(record.id) }
        assertTrue(full.sentences.isNotEmpty())
    }

    @Test fun legacyIndexesFinishMigrationAndReturnTheRebasedCursorAndFullBook() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val full = repository.loadSpeechBook(record.id)
        val metadata = file(record, "book.json")
        metadata.writeText(JSONObject(metadata.readText()).apply {
            remove("textIndexVersion")
            put("sentenceCount", full.sentences.size + 1)
        }.toString())
        val legacy = JSONArray().put(legacyEntry(0, "旧版额外标题。", full.sentences[0].locatorJson))
        for (sentence in full.sentences) legacy.put(legacyEntry(sentence.index + 1, sentence.text, sentence.locatorJson))
        index(record).writeText(JSONObject().put("version", 1).put("bookId", record.id)
            .put("sentences", legacy).toString())
        val saved = ReadingProgress(record.id, full.sentences[2].locatorJson, 3, 2_345, 67)
        file(record, "progress.json").writeText(JSONObject().put("bookId", record.id)
            .put("locatorJson", saved.locatorJson).put("sentenceIndex", saved.sentenceIndex)
            .put("audioOffsetMs", saved.audioOffsetMs).put("updatedAt", saved.updatedAt).toString())

        val bootstrap = BookRepository(app).prepareReadingBook(record.id)

        assertEquals(full.book, bootstrap.record)
        assertNotNull(bootstrap.cachedSpeechBook)
        assertEquals(full.sentences.map { it.text }, bootstrap.cachedSpeechBook!!.sentences.map { it.text })
        assertEquals(2, bootstrap.progress.sentenceIndex)
        assertEquals(full.sentences[2].locatorJson, bootstrap.progress.locatorJson)
        assertEquals(saved.audioOffsetMs, bootstrap.progress.audioOffsetMs)
        assertEquals(2, JSONObject(metadata.readText()).getInt("textIndexVersion"))
    }

    @Test fun aDeferredSaveCannotWriteIntoAReimportedBookWithTheSameHash() = runBlocking {
        val imported = repository.importBook(Uri.fromFile(fixture))
        val metadata = file(imported, "book.json")
        metadata.writeText(JSONObject(metadata.readText()).put("addedAt", 1L).toString())
        val oldGeneration = repository.prepareReadingBook(imported.id)
        val oldCursor = ReadingProgress(imported.id, sentenceIndex = 2, updatedAt = 500)
        repository.saveProgress(oldCursor, expectedAddedAt = oldGeneration.record.addedAt)
        assertEquals(oldCursor, repository.progress(imported.id))
        repository.removeBook(imported.id)
        val reimported = repository.importBook(Uri.fromFile(fixture))
        assertEquals(imported.id, reimported.id)
        assertTrue(reimported.addedAt != oldGeneration.record.addedAt)

        repository.saveProgress(oldCursor.copy(updatedAt = 900), expectedAddedAt = oldGeneration.record.addedAt)

        assertEquals(ReadingProgress(imported.id, updatedAt = 0), repository.progress(imported.id))
        assertFalse(file(reimported, "progress.json").exists())
    }

    @Test fun guardedWritesForTheSameGenerationStillRejectOlderCapturedTimestamps() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val newest = ReadingProgress(record.id, sentenceIndex = 2, updatedAt = 200)
        repository.saveProgress(newest, expectedAddedAt = record.addedAt)
        assertEquals(newest, repository.progress(record.id))

        repository.saveProgress(newest.copy(sentenceIndex = 1, updatedAt = 100), expectedAddedAt = record.addedAt)

        assertEquals(newest, repository.progress(record.id))
        assertEquals(2, JSONObject(file(record, "progress.json").readText()).getInt("sentenceIndex"))
    }

    @Test fun unreadableMetadataConservativelyRejectsAGuardedWriteWithoutThrowing() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        file(record, "book.json").writeText("unreadable metadata")

        repository.saveProgress(ReadingProgress(record.id, sentenceIndex = 2), expectedAddedAt = record.addedAt)

        assertFalse(file(record, "progress.json").exists())
    }

    @Test fun reopeningWaitsForThePendingFinalSaveBeforeReadingItsCursorAndReleasesTheRegistry() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val before = ReadingProgress(record.id, sentenceIndex = 1, updatedAt = 100)
        val after = before.copy(sentenceIndex = 2, updatedAt = 200)
        repository.saveProgress(before)
        val finalSave = Job()
        repository.registerReadingSave(record.id, record.addedAt, finalSave)
        val opening = async(start = CoroutineStart.UNDISPATCHED) { repository.prepareReadingBook(record.id) }
        try {
            assertNull(withTimeoutOrNull(100) { opening.await() })
            repository.saveProgress(after, expectedAddedAt = record.addedAt)
            finalSave.complete()

            assertEquals(after, withTimeout(3_000) { opening.await() }.progress)
            val pending: Map<*, *> = ReflectionHelpers.getField(repository, "readingSaves")
            assertTrue(pending.isEmpty())
        } finally {
            finalSave.complete()
            opening.cancelAndJoin()
        }
    }

    @Test fun allPendingSavesOfTheSameBookMustFinishRatherThanOnlyTheLatestRegisteredOne() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val first = Job()
        val second = Job()
        repository.registerReadingSave(record.id, record.addedAt, first)
        repository.registerReadingSave(record.id, record.addedAt, second)
        val opening = async(start = CoroutineStart.UNDISPATCHED) { repository.prepareReadingBook(record.id) }
        try {
            second.complete()
            assertNull(withTimeoutOrNull(100) { opening.await() })
            val expected = ReadingProgress(record.id, sentenceIndex = 2, updatedAt = 200)
            repository.saveProgress(expected, expectedAddedAt = record.addedAt)
            first.complete()

            assertEquals(expected, withTimeout(3_000) { opening.await() }.progress)
        } finally {
            first.complete()
            second.complete()
            opening.cancelAndJoin()
        }
    }

    @Test fun aDifferentBookDoesNotWaitForAnUnfinishedReadersFinalSave() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val secondFile = OriginalEpubFixture.repeatedParagraph(File(app.cacheDir, "bootstrap-other.epub"), 10)
        val other = repository.importBook(Uri.fromFile(secondFile))
        val finalSave = Job()
        repository.registerReadingSave(record.id, record.addedAt, finalSave)
        try {
            assertEquals(other, withTimeout(3_000) { repository.prepareReadingBook(other.id) }.record)
            assertFalse(finalSave.isCompleted)
        } finally {
            finalSave.complete()
            secondFile.delete()
        }
    }

    @Test fun aReimportedGenerationDoesNotWaitForTheRemovedReadersFinalSave() = runBlocking {
        val record = repository.importBook(Uri.fromFile(fixture))
        val metadata = file(record, "book.json")
        metadata.writeText(JSONObject(metadata.readText()).put("addedAt", 1L).toString())
        val oldGeneration = repository.prepareReadingBook(record.id)
        val finalSave = Job()
        repository.registerReadingSave(record.id, oldGeneration.record.addedAt, finalSave)
        try {
            repository.removeBook(record.id)
            val reimported = repository.importBook(Uri.fromFile(fixture))

            assertEquals(reimported,
                withTimeout(3_000) { repository.prepareReadingBook(reimported.id) }.record)
            assertFalse(finalSave.isCompleted)
        } finally {
            finalSave.complete()
        }
    }

    private fun legacyEntry(index: Int, text: String, locator: String) = JSONObject().put("index", index)
        .put("text", text).put("locatorJson", locator).put("chapterIndex", 0).put("chapterTitle", "第1章")
    private fun index(record: BookRecord) = file(record, "speech.json")
    private fun file(record: BookRecord, name: String) = File(repository.bookFile(record.id).parentFile, name)
    private suspend inline fun expectIo(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid or missing storage to fail")
        } catch (_: IOException) { }
    }
}
