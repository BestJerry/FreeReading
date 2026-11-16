package com.freereading.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.freereading.ReadingApplication
import com.freereading.epub.BookImportException
import com.freereading.epub.SentenceIndexer
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class BookImportProgressPipelineTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository
    private lateinit var fixtures: File

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        fixtures = File(app.cacheDir, "import-progress-fixtures").apply { mkdirs() }
        repository = BookRepository(app)
    }

    @After fun clean() {
        File(app.filesDir, "books").deleteRecursively()
        fixtures.deleteRecursively()
    }

    @Test fun realImportReportsEveryStageAndCompletesOnlyAfterTheBookIsCommitted() = runBlocking {
        val file = sample()
        val events = ArrayList<BookImportProgress>()
        val record = repository.importBook(Uri.fromFile(file)) { progress ->
            events.add(progress)
            if (progress.stage == ImportStage.COMPLETED) {
                val committed = repository.books.value.single()
                assertTrue(repository.bookFile(committed.id).isFile)
                assertTrue(File(repository.bookFile(committed.id).parentFile, "book.json").isFile)
                assertTrue(File(repository.bookFile(committed.id).parentFile, "speech.json").isFile)
            }
        }

        assertEquals(ImportStage.entries, stageChanges(events))
        val copying = events.filter { it.stage == ImportStage.COPYING }
        assertEquals(file.length(), copying.first().total)
        assertEquals(file.length(), copying.last().completed)
        assertEquals(file.length(), copying.last().total)
        val validating = events.last { it.stage == ImportStage.VALIDATING }
        val indexing = events.last { it.stage == ImportStage.INDEXING }
        assertEquals(3L, validating.total)
        assertEquals(validating.total, validating.completed)
        assertEquals(3L, indexing.total)
        assertEquals(indexing.total, indexing.completed)
        assertTrue(record.sentenceCount > indexing.completed)
        val saving = events.last { it.stage == ImportStage.SAVING }
        assertTrue(requireNotNull(saving.total) > record.sentenceCount)
        assertEquals(saving.total, saving.completed)
        assertEquals(ImportStage.COMPLETED, events.last().stage)
        assertTrue(events.all { it.fraction == null || it.fraction!! in 0f..1f })
    }

    @Test fun unknownProviderSizeStaysIndeterminateAndOpensTheDataOnlyOnce() = runBlocking {
        val file = sample()
        val provider = provider(file, declaredSize = null)
        val events = ArrayList<BookImportProgress>()
        repository.importBook(contentUri) { events.add(it) }

        val copying = events.filter { it.stage == ImportStage.COPYING }
        assertEquals(null, copying.first().total)
        assertTrue(copying.dropLast(1).all { it.total == null })
        assertEquals(file.length(), copying.last().completed)
        assertEquals(file.length(), copying.last().total)
        assertEquals(1, provider.dataOpens)
    }

    @Test fun anIncorrectProviderSizeCannotFinishCopyingBeforeTheActualEndOfInput() = runBlocking {
        val file = sample()
        val provider = provider(file, declaredSize = 1)
        val events = ArrayList<BookImportProgress>()
        repository.importBook(contentUri) { events.add(it) }

        val copying = events.filter { it.stage == ImportStage.COPYING }
        assertEquals(1L, copying.first().total)
        assertTrue(copying.none { it.completed == 1L })
        assertTrue(copying.any { it.completed > 1 && it.total == null })
        assertEquals(file.length(), copying.last().completed)
        assertEquals(file.length(), copying.last().total)
        assertEquals(1, provider.dataOpens)
    }

    @Test fun duplicateImportReportsCopyThenCompletionWithoutPretendingToReindex() = runBlocking {
        val file = sample()
        val first = repository.importBook(Uri.fromFile(file))
        val events = ArrayList<BookImportProgress>()
        val second = repository.importBook(Uri.fromFile(file)) { events.add(it) }

        assertEquals(first.id, second.id)
        assertEquals(listOf(ImportStage.WAITING, ImportStage.COPYING, ImportStage.COMPLETED), stageChanges(events))
        assertEquals(1, repository.books.value.size)
    }

    @Test fun brokenArchiveNeverReportsCompletionAndLeavesNoStagingDirectory() = runBlocking {
        val file = File(fixtures, "broken.epub").apply { writeText("broken archive") }
        val events = ArrayList<BookImportProgress>()
        try {
            repository.importBook(Uri.fromFile(file)) { events.add(it) }
            fail("Expected a damaged EPUB to fail")
        } catch (_: BookImportException) { }
        assertEquals(ImportStage.VALIDATING, events.last().stage)
        assertFalse(events.any { it.stage == ImportStage.COMPLETED })
        assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAtIndexingKeepsTheBookUncommittedAndCleansTheStagingDirectory() = runBlocking {
        val events = ArrayList<BookImportProgress>()
        try {
            repository.importBook(Uri.fromFile(sample())) {
                events.add(it)
                if (it.stage == ImportStage.INDEXING) throw CancellationException("test cancellation")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(events.any { it.stage == ImportStage.COMPLETED })
        assertTrue(repository.books.value.isEmpty())
        assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    @Test fun validationAndIndexingCountReadingOrderResourcesIncludingEmptyAndImageChapters() = runBlocking {
        val original = OriginalEpubFixture.numberedChapters(File(fixtures, "four-chapters.epub"),
            totalSentences = 8, sentencesPerChapter = 2)
        val file = resourceFixture(original)
        val validation = ArrayList<Pair<Long, Long?>>()
        val publication = repository.readium.open(file) { done, total -> validation.add(done to total) }
        try {
            assertEquals(listOf(0L to null, 0L to 4L, 1L to 4L, 2L to 4L, 3L to 4L, 4L to 4L), validation)
            val indexing = ArrayList<Pair<Long, Long>>()
            val sentences = SentenceIndexer().index(publication) { done, total -> indexing.add(done to total) }
            assertEquals(0L to 4L, indexing.first())
            assertEquals(4L to 4L, indexing.last())
            assertTrue(indexing.contains(2L to 4L))
            assertTrue(indexing.zipWithNext().all { (first, second) -> first.first <= second.first })
            assertEquals(setOf(0, 2), sentences.map { it.chapterIndex }.toSet())
        } finally {
            publication.close()
        }
    }

    private fun sample(): File = File(fixtures, "original-sample.epub").apply {
        this@BookImportProgressPipelineTest.javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }

    private fun stageChanges(events: List<BookImportProgress>): List<ImportStage> =
        events.map { it.stage }.filterIndexed { index, stage -> index == 0 || stage != events[index - 1].stage }

    private fun provider(file: File, declaredSize: Long?): MetadataProvider {
        val provider = MetadataProvider(file, declaredSize)
        provider.attachInfo(app, ProviderInfo().apply {
            authority = contentUri.authority
            applicationInfo = app.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal(contentUri.authority!!, provider)
        return provider
    }

    private class MetadataProvider(private val file: File, private val declaredSize: Long?) : ContentProvider() {
        var dataOpens = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/epub+zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> when (column) {
                    OpenableColumns.SIZE -> declaredSize
                    OpenableColumns.DISPLAY_NAME -> "original.epub"
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            dataOpens += 1
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private fun resourceFixture(original: File): File {
        val target = File(fixtures, "resource-counts.epub")
        ZipFile(original).use { source ->
            ZipOutputStream(target.outputStream()).use { output ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    var bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "EPUB/package.opf") bytes = bytes.toString(Charsets.UTF_8)
                        .replace("</manifest>", "<item id=\"picture\" href=\"picture.png\" media-type=\"image/png\"/></manifest>")
                        .toByteArray(Charsets.UTF_8)
                    if (entry.name in setOf("EPUB/chapter-1.xhtml", "EPUB/chapter-3.xhtml")) {
                        val body = if (entry.name.endsWith("1.xhtml")) "<img src=\"picture.png\" alt=\"\"/>" else ""
                        bytes = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>资源</title></head><body>$body</body></html>"
                            .toByteArray(Charsets.UTF_8)
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
                output.putNextEntry(ZipEntry("EPUB/picture.png"))
                output.write(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="))
                output.closeEntry()
            }
        }
        return target
    }

    companion object { private val contentUri = Uri.parse("content://freereading.progress.test/book") }
}
