package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class BookRepositoryMigrationTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository
    private lateinit var fixtures: File

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        fixtures = File(app.cacheDir, "index-migration-fixtures").apply { mkdirs() }
        repository = BookRepository(app)
    }

    @After fun clean() {
        File(app.filesDir, "books").deleteRecursively()
        fixtures.deleteRecursively()
    }

    @Test fun legacyBomBookIsAutomaticallyReindexedAndItsListeningCursorIsRebased() = runBlocking {
        val seed = seedLegacyBook()
        repository.refresh()
        assertEquals(seed.current.sentences.size + 1, repository.books.value.single().sentenceCount)

        val migrated = repository.loadSpeechBook(seed.current.book.id)

        assertEquals(seed.current.book, migrated.book)
        assertEquals(seed.current.sentences.map { it.text }, migrated.sentences.map { it.text })
        val progress = repository.progress(migrated.book.id)
        assertEquals(2, progress.sentenceIndex)
        assertEquals(seed.current.sentences[2].locatorJson, progress.locatorJson)
        assertEquals(seed.saved.audioOffsetMs, progress.audioOffsetMs)
        assertEquals(2, json(seed, "progress.json").getInt("textIndexVersion"))
        assertEquals(1, json(seed, "book.json").getInt("version"))
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
        assertEquals(2, json(seed, "speech.json").getInt("version"))
        assertEquals(migrated.sentences.size, repository.books.value.single().sentenceCount)
        assertArrayEquals(seed.epubBytes, repository.bookFile(migrated.book.id).readBytes())
    }

    @Test fun restartingAfterMigrationDoesNotRewriteTheIndexOrRebaseProgressAgain() = runBlocking {
        val seed = seedLegacyBook()
        repository.loadSpeechBook(seed.current.book.id)
        val index = File(seed.directory, "speech.json")
        assertTrue(index.setLastModified(120_000L))
        val progressBefore = File(seed.directory, "progress.json").readText()

        val restarted = BookRepository(app)
        val reopened = restarted.loadSpeechBook(seed.current.book.id)

        assertEquals(seed.current.sentences.size, reopened.sentences.size)
        assertEquals(120_000L, index.lastModified())
        assertEquals(progressBefore, File(seed.directory, "progress.json").readText())
        assertEquals(2, restarted.progress(reopened.book.id).sentenceIndex)
    }

    @Test fun interruptionAfterIndexWriteRetriesWithoutReadingItAgainstTheOldCount() = runBlocking {
        val seed = seedLegacyBook()
        SpeechIndexStore().write(File(seed.directory, "speech.json"), seed.current)
        assertEquals(seed.current.sentences.size + 1, json(seed, "book.json").getInt("sentenceCount"))
        assertEquals(seed.current.sentences.size, json(seed, "speech.json").getJSONArray("sentences").length())

        val migrated = BookRepository(app).loadSpeechBook(seed.current.book.id)

        assertEquals(seed.current.sentences.size, migrated.sentences.size)
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
        assertEquals(2, json(seed, "progress.json").getInt("sentenceIndex"))
        assertEquals(seed.saved.audioOffsetMs, json(seed, "progress.json").getLong("audioOffsetMs"))
    }

    @Test fun interruptionAfterRebasedProgressWriteKeepsTheVersionTwoCheckpoint() = runBlocking {
        val seed = seedLegacyBook()
        SpeechIndexStore().write(File(seed.directory, "speech.json"), seed.current)
        val checkpointLocator = JSONObject(seed.current.sentences[2].locatorJson).apply {
            getJSONObject("locations").put("migrationCheckpoint", "already-rebased")
        }.toString()
        val checkpoint = seed.saved.copy(sentenceIndex = 2, locatorJson = checkpointLocator, updatedAt = 100)
        writeProgress(seed.directory, checkpoint, textVersion = 2)

        val restarted = BookRepository(app)
        restarted.loadSpeechBook(seed.current.book.id)

        assertEquals(checkpoint, restarted.progress(seed.current.book.id))
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
        assertEquals(checkpointLocator, json(seed, "progress.json").getString("locatorJson"))
    }

    @Test fun missingProgressGetsAZeroTimestampCheckpointBeforeMetadataIsCommitted() = runBlocking {
        val seed = seedLegacyBook()
        assertTrue(File(seed.directory, "progress.json").delete())

        repository.loadSpeechBook(seed.current.book.id)

        val progress = repository.progress(seed.current.book.id)
        assertEquals(0L, progress.updatedAt)
        assertEquals(0, progress.sentenceIndex)
        assertEquals(0L, progress.audioOffsetMs)
        assertEquals(2, json(seed, "progress.json").getInt("textIndexVersion"))
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
    }

    @Test fun failedProgressWriteDoesNotCommitMetadataAndTheMigrationCanBeRetried() = runBlocking {
        val seed = seedLegacyBook()
        val progress = File(seed.directory, "progress.json")
        blockProgressWrites(progress)

        expectIo { repository.loadSpeechBook(seed.current.book.id) }

        assertEquals(1, json(seed, "book.json").optInt("textIndexVersion", 1))
        assertEquals(seed.current.sentences.size + 1, json(seed, "book.json").getInt("sentenceCount"))
        assertEquals(2, json(seed, "speech.json").getInt("version"))
        assertTrue(progress.isDirectory)
        assertTrue(File(progress, "blocker").isFile)
        unblockProgressWrites(progress)
        val migrated = repository.loadSpeechBook(seed.current.book.id)
        assertEquals(seed.current.sentences.size, migrated.sentences.size)
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
    }

    @Test fun normalWritesBeforeMigrationKeepTheLegacyMarkerAndThenUseTheNewVersion() = runBlocking {
        val seed = seedLegacyBook()
        repository.saveProgress(seed.saved.copy(updatedAt = 200))
        assertEquals(1, json(seed, "progress.json").getInt("textIndexVersion"))

        repository.loadSpeechBook(seed.current.book.id)
        val migrated = repository.progress(seed.current.book.id)
        assertEquals(2, migrated.sentenceIndex)
        repository.saveProgress(migrated.copy(updatedAt = 201))
        assertEquals(2, json(seed, "progress.json").getInt("textIndexVersion"))
    }

    @Test fun failedAtomicFinishCannotCommitTheNewMetadataMarker() = runBlocking {
        val seed = seedLegacyBook()
        val progress = File(seed.directory, "progress.json")
        assertTrue(progress.delete())
        assertTrue(progress.mkdir())
        File(progress, "blocker").writeText("Prevent the committed file from replacing this directory")
        // .new/.bak are intentionally writable and absent: startWrite can succeed. A modern
        // rename or a legacy backup cleanup fails during finishWrite, which may only log it.
        assertTrue(!File(progress.path + ".new").exists())
        assertTrue(!File(progress.path + ".bak").exists())

        expectIo { repository.loadSpeechBook(seed.current.book.id) }

        assertEquals(1, json(seed, "book.json").optInt("textIndexVersion", 1))
        assertEquals(seed.current.sentences.size + 1, json(seed, "book.json").getInt("sentenceCount"))
        assertTrue(progress.isDirectory)
        assertTrue(File(progress, "blocker").isFile)
        unblockProgressWrites(progress)
        val migrated = repository.loadSpeechBook(seed.current.book.id)
        assertEquals(seed.current.sentences.size, migrated.sentences.size)
        assertEquals(2, json(seed, "book.json").getInt("textIndexVersion"))
    }

    @Test fun latestInMemoryProgressSurvivesAFailedNormalWriteBeforeMigration() = runBlocking {
        val seed = seedLegacyBook()
        val path = File(seed.directory, "progress.json")
        blockProgressWrites(path)
        val latest = seed.saved.copy(audioOffsetMs = 3_456, updatedAt = 300)
        repository.saveProgress(latest)
        assertEquals(latest, repository.progress(seed.current.book.id))
        assertTrue("The normal writer must really fail instead of replacing the obstruction", path.isDirectory)
        assertTrue(File(path, "blocker").isFile)
        assertTrue(File(path.path + ".new").isDirectory)
        unblockProgressWrites(path)

        repository.loadSpeechBook(seed.current.book.id)

        val migrated = repository.progress(seed.current.book.id)
        assertEquals(2, migrated.sentenceIndex)
        assertEquals(3_456L, migrated.audioOffsetMs)
        assertEquals(2, json(seed, "progress.json").getInt("textIndexVersion"))
    }

    @Test fun badMetadataDuringANormalWriteDoesNotThrowOrDiscardTheLiveCursor() = runBlocking {
        val seed = seedLegacyBook()
        File(seed.directory, "book.json").writeText("{")
        val latest = seed.saved.copy(audioOffsetMs = 4_567, updatedAt = 400)

        repository.saveProgress(latest)

        assertEquals(latest, repository.progress(seed.current.book.id))
        assertEquals(1, json(seed, "progress.json").getInt("textIndexVersion"))
    }

    @Test fun normalVisualProgressInANoBomV2BookKeepsThePageAndMiddleSentence() = runBlocking {
        val seed = seedLegacyBook(bom = false, rangeFormat = true)
        val visual = visualProgress(seed)
        writeProgress(seed.directory, visual)

        BookRepository(app).loadSpeechBook(seed.current.book.id)

        val migrated = BookRepository(app).progress(seed.current.book.id)
        assertEquals(visual, migrated)
        assertEquals(2, migrated.sentenceIndex)
        assertArrayEquals(seed.epubBytes, repository.bookFile(seed.current.book.id).readBytes())
        assertFalse(File(seed.directory, "speech-legacy.json").exists())
    }

    @Test fun normalVisualProgressInAV1BomBookSkipsEverySyntheticTitleSentence() = runBlocking {
        val seed = seedLegacyBook(headSentences = 3)
        val visual = visualProgress(seed)
        assertEquals(5, visual.sentenceIndex)
        writeProgress(seed.directory, visual)

        BookRepository(app).loadSpeechBook(seed.current.book.id)

        val migrated = BookRepository(app).progress(seed.current.book.id)
        assertEquals(2, migrated.sentenceIndex)
        assertEquals(seed.current.sentences[2].text,
            repository.loadSpeechBook(seed.current.book.id).sentences[migrated.sentenceIndex].text)
        assertEquals(visual.locatorJson, migrated.locatorJson)
        assertEquals(visual.updatedAt, migrated.updatedAt)
        assertEquals(0L, migrated.audioOffsetMs)
        assertArrayEquals(seed.epubBytes, repository.bookFile(seed.current.book.id).readBytes())
    }

    @Test fun aRetryUsesThePreservedOldIndexAfterTheNewIndexHasAlreadyBeenWritten() = runBlocking {
        val seed = seedLegacyBook(headSentences = 3, rangeFormat = true)
        val visual = visualProgress(seed)
        writeProgress(seed.directory, visual)
        val legacy = File(seed.directory, "speech-legacy.json")
        assertTrue(File(seed.directory, "speech.json").renameTo(legacy))
        val legacyBytes = legacy.readBytes()
        SpeechIndexStore().write(File(seed.directory, "speech.json"), seed.current)

        val restarted = BookRepository(app)
        restarted.loadSpeechBook(seed.current.book.id)

        assertEquals(2, restarted.progress(seed.current.book.id).sentenceIndex)
        assertEquals(visual.locatorJson, restarted.progress(seed.current.book.id).locatorJson)
        assertFalse(legacy.exists())
        assertTrue(legacyBytes.size > 0)
    }

    @Test fun aRetryKeepsAnAlreadyRebasedVisualCheckpointAndCleansTheLegacyIndex() = runBlocking {
        val seed = seedLegacyBook(headSentences = 3)
        assertTrue(File(seed.directory, "speech.json").renameTo(File(seed.directory, "speech-legacy.json")))
        SpeechIndexStore().write(File(seed.directory, "speech.json"), seed.current)
        val checkpoint = visualProgress(seed).copy(sentenceIndex = 2)
        writeProgress(seed.directory, checkpoint, textVersion = 2)

        val restarted = BookRepository(app)
        restarted.loadSpeechBook(seed.current.book.id)

        assertEquals(checkpoint, restarted.progress(seed.current.book.id))
        assertFalse(File(seed.directory, "speech-legacy.json").exists())
    }

    @Test fun duplicateImportRemainsUsableAfterTheOldIndexWasMovedAside() = runBlocking {
        val seed = seedLegacyBook()
        val visual = visualProgress(seed)
        writeProgress(seed.directory, visual)
        assertTrue(File(seed.directory, "speech.json").renameTo(File(seed.directory, "speech-legacy.json")))

        val restarted = BookRepository(app)
        val imported = restarted.importBook(Uri.fromFile(repository.bookFile(seed.current.book.id)))
        restarted.loadSpeechBook(imported.id)

        assertEquals(seed.current.book.id, imported.id)
        assertEquals(2, restarted.progress(imported.id).sentenceIndex)
        assertEquals(visual.locatorJson, restarted.progress(imported.id).locatorJson)
    }

    @Test fun aNewIndexWithAnOldMetadataCountCannotMasqueradeAsOldSentenceEvidence() = runBlocking {
        val seed = seedLegacyBook(headSentences = 3)
        val visual = visualProgress(seed)
        writeProgress(seed.directory, visual)
        // This is an interruption left by the previous migration implementation: it did not
        // preserve its old index, so there is no reliable evidence for this visual cursor.
        SpeechIndexStore().write(File(seed.directory, "speech.json"), seed.current)

        val restarted = BookRepository(app)
        restarted.loadSpeechBook(seed.current.book.id)

        assertEquals(0, restarted.progress(seed.current.book.id).sentenceIndex)
        assertEquals(visual.locatorJson, restarted.progress(seed.current.book.id).locatorJson)
    }

    @Test fun committedMetadataCleansUpALegacyFileWithoutRewritingTheCurrentIndex() = runBlocking {
        val seed = seedLegacyBook()
        repository.loadSpeechBook(seed.current.book.id)
        val legacy = File(seed.directory, "speech-legacy.json").apply { writeText("cleanup after a process exit") }
        val index = File(seed.directory, "speech.json")
        assertTrue(index.setLastModified(120_000))

        BookRepository(app).loadSpeechBook(seed.current.book.id)

        assertFalse(legacy.exists())
        assertEquals(120_000L, index.lastModified())
    }

    private data class Seed(
        val current: SpeechBook,
        val directory: File,
        val saved: ReadingProgress,
        val epubBytes: ByteArray,
    )

    private suspend fun seedLegacyBook(bom: Boolean = true, headSentences: Int = 1,
                                       rangeFormat: Boolean = false): Seed {
        val clean = OriginalEpubFixture.numberedChapters(File(fixtures, "original.epub"),
            totalSentences = 4, sentencesPerChapter = 4, sentencesPerParagraph = 1)
        val input = if (bom) addBomToBodyResource(clean, headSentences) else clean
        val headCount = if (bom) headSentences else 0
        val record = repository.importBook(Uri.fromFile(input))
        val current = repository.loadSpeechBook(record.id)
        val directory = requireNotNull(repository.bookFile(record.id).parentFile)
        assertEquals(2, JSONObject(File(directory, "book.json").readText()).getInt("textIndexVersion"))
        val metadata = JSONObject(File(directory, "book.json").readText()).apply {
            remove("textIndexVersion")
            put("sentenceCount", current.sentences.size + headCount)
        }
        File(directory, "book.json").writeText(metadata.toString())
        val entries = JSONArray()
        repeat(headCount) { index ->
            val text = "旧版误入正文的标题${index + 1}。"
            val titleLocator = JSONObject(current.sentences.first().locatorJson).apply {
                getJSONObject("text").put("highlight", text)
                getJSONObject("locations").put("cssSelector", "body > title")
            }.toString()
            entries.put(legacyEntry(index, text, titleLocator))
        }
        for (sentence in current.sentences) entries.put(legacyEntry(sentence.index + headCount, sentence.text,
            if (bom) shiftedLocator(sentence) else sentence.locatorJson))
        val indexFile = File(directory, "speech.json")
        if (rangeFormat) {
            val old = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                SpeechSentence(index, entry.getString("text"), entry.getString("locatorJson"),
                    entry.getInt("chapterIndex"), entry.getString("chapterTitle"))
            }
            SpeechIndexStore().write(indexFile, SpeechBook(current.book.copy(sentenceCount = old.size), old))
        } else {
            indexFile.writeText(JSONObject().put("version", 1).put("bookId", record.id)
                .put("sentences", entries).toString())
        }
        val target = current.sentences[2]
        val saved = ReadingProgress(record.id, if (bom) shiftedLocator(target) else target.locatorJson,
            target.index + headCount, 2_345, 67)
        writeProgress(directory, saved)
        return Seed(current, directory, saved, repository.bookFile(record.id).readBytes())
    }

    private fun legacyEntry(index: Int, text: String, locator: String) = JSONObject().put("index", index)
        .put("text", text).put("locatorJson", locator).put("chapterIndex", 0).put("chapterTitle", "第1章")

    private fun shiftedLocator(sentence: SpeechSentence): String = JSONObject(sentence.locatorJson).apply {
        getJSONObject("locations").put("cssSelector", "body > p:nth-child(${sentence.index + 2})")
    }.toString()

    private fun visualProgress(seed: Seed): ReadingProgress {
        val visual = JSONObject(seed.current.sentences[2].locatorJson).apply {
            remove("text")
            put("locations", JSONObject().put("progression", 0.72))
        }.toString()
        return seed.saved.copy(locatorJson = visual, audioOffsetMs = 0)
    }

    private fun writeProgress(directory: File, progress: ReadingProgress, textVersion: Int? = null) {
        val json = JSONObject().put("bookId", progress.bookId).put("locatorJson", progress.locatorJson)
            .put("sentenceIndex", progress.sentenceIndex).put("audioOffsetMs", progress.audioOffsetMs)
            .put("updatedAt", progress.updatedAt)
        if (textVersion != null) json.put("textIndexVersion", textVersion)
        File(directory, "progress.json").writeText(json.toString())
    }

    private fun json(seed: Seed, name: String): JSONObject = JSONObject(File(seed.directory, name).readText())

    private fun blockProgressWrites(path: File) {
        // AtomicFile uses .bak on older Android and .new on newer versions. Nonempty
        // directories prevent both implementations from replacing the intended file.
        for (file in listOf(path, File(path.path + ".bak"), File(path.path + ".new"))) {
            assertTrue(file.deleteRecursively())
            assertTrue(file.mkdir())
            File(file, "blocker").writeText("Keep this directory nonempty")
        }
    }

    private fun unblockProgressWrites(path: File) {
        for (file in listOf(path, File(path.path + ".bak"), File(path.path + ".new"))) {
            assertTrue(file.deleteRecursively())
        }
    }

    private fun addBomToBodyResource(source: File, headSentences: Int): File {
        val target = File(fixtures, "original-bom.epub")
        ZipFile(source).use { input ->
            ZipOutputStream(target.outputStream()).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    var bytes = input.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "EPUB/chapter-0.xhtml") {
                        val title = (1..headSentences).joinToString("") { "旧版误入正文的标题${it}。" }
                        bytes = bytes.toString(Charsets.UTF_8).replace("<title>第1章</title>", "<title>$title</title>")
                            .toByteArray(Charsets.UTF_8)
                        bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
                    }
                    output.putNextEntry(ZipEntry(entry.name).apply {
                        if (entry.name == "mimetype") {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private suspend inline fun expectIo(action: () -> Unit) {
        try {
            action()
            fail("Expected migration persistence to fail")
        } catch (_: IOException) { }
    }
}
