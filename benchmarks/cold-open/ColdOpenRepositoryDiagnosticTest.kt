package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Temporary measurement probe: app-cache cold, with JVM and OS caches explicitly uncontrolled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ColdOpenRepositoryDiagnosticTest {
    private lateinit var app: ReadingApplication
    private lateinit var fixtureDirectory: File

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        fixtureDirectory = File(app.cacheDir, "cold-open-original-fixtures").apply { mkdirs() }
    }

    @After fun clean() {
        File(app.filesDir, "books").deleteRecursively()
        fixtureDirectory.deleteRecursively()
    }

    @Test fun reportsMetadataProgressAndColdIndexLoadForSmallAndLargeOriginalBooks() = runBlocking {
        val sample = File(fixtureDirectory, "small.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val small = BookRepository(app).importBook(Uri.fromFile(sample))
        val smallResult = measure(small)
        val large = seedLargeIndex(180_000)
        val largeResult = measure(large)
        println("COLD_OPEN_REPOSITORY_DIAGNOSTIC " + JSONObject().put("small", smallResult)
            .put("large", largeResult).put("scope", "fresh repository; OS/JVM caches not controlled"))
    }

    private suspend fun measure(record: BookRecord): JSONObject {
        val recreated = BookRepository(app)
        val index = File(recreated.bookFile(record.id).parentFile, "speech.json")
        var started = System.nanoTime()
        recreated.refresh()
        val metadataMs = elapsed(started)
        started = System.nanoTime()
        val progress = recreated.progress(record.id)
        val progressMs = elapsed(started)
        System.gc()
        val bootstrapHeapBefore = usedHeap()
        started = System.nanoTime()
        val bootstrap = recreated.prepareReadingBook(record.id)
        val bootstrapMs = elapsed(started)
        System.gc()
        val bootstrapHeapDelta = usedHeap() - bootstrapHeapBefore
        assertEquals(record, bootstrap.record)
        assertEquals(progress, bootstrap.progress)
        assertNull(bootstrap.cachedSpeechBook)
        started = System.nanoTime()
        index.inputStream().buffered().use { input ->
            input.copyTo(object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            })
        }
        val rawIndexReadMs = elapsed(started)
        System.gc()
        val heapBefore = usedHeap()
        started = System.nanoTime()
        val cold = recreated.loadSpeechBook(record.id)
        val coldMs = elapsed(started)
        System.gc()
        val heapAfter = usedHeap()
        started = System.nanoTime()
        val warm = recreated.loadSpeechBook(record.id)
        val warmMs = elapsed(started)
        assertEquals(record.sentenceCount, cold.sentences.size)
        assertSame(cold, warm)
        assertEquals(record.id, progress.bookId)
        return JSONObject().put("sentenceCount", cold.sentences.size).put("indexBytes", index.length())
            .put("metadataRefreshMs", metadataMs).put("progressReadMs", progressMs)
            .put("readingBootstrapMs", bootstrapMs).put("readingBootstrapHeapDeltaBytes", bootstrapHeapDelta)
            .put("rawIndexReadMs", rawIndexReadMs).put("coldSpeechBookMs", coldMs)
            .put("warmSpeechBookMs", warmMs).put("retainedHeapDeltaBytes", heapAfter - heapBefore)
    }

    private suspend fun seedLargeIndex(count: Int): BookRecord {
        val id = "a".repeat(64)
        val directory = File(app.filesDir, "books/$id").apply { mkdirs() }
        OriginalEpubFixture.numberedChapters(File(directory, "book.epub"), totalSentences = count,
            sentencesPerChapter = 1_000, sentencesPerParagraph = 1)
        val record = BookRecord(id, "溪谷冷加载原创诊断", "FreeReading 原创测试", "zh", 1L, count)
        val sentences = (0 until count).map { index ->
            val chapter = index / 1_000
            val chapterStart = chapter * 1_000
            val chapterEnd = minOf(count, chapterStart + 1_000)
            val text = sentenceText(index)
            val source = SpeechSource(Locator(requireNotNull(Url("EPUB/chapter-$chapter.xhtml")),
                MediaType.XHTML, title = "第${chapter + 1}章",
                locations = Locator.Locations(otherLocations = mapOf(
                    "cssSelector" to "html > body > p:nth-of-type(${index % 1_000 + 1})")),
                text = Locator.Text(
                    before = (maxOf(chapterStart, index - 2) until index).joinToString("") { sentenceText(it) },
                    highlight = text,
                    after = (index + 1 until minOf(chapterEnd, index + 3)).joinToString("") { sentenceText(it) },
                )), chapter, "第${chapter + 1}章")
            SpeechSentence(index, source, 0, text.length)
        }
        SpeechIndexStore().write(File(directory, "speech.json"), SpeechBook(record, sentences))
        File(directory, "book.json").writeText(JSONObject().put("version", 1).put("textIndexVersion", 2)
            .put("id", id).put("title", record.title).put("author", record.author)
            .put("language", record.language).put("addedAt", record.addedAt)
            .put("sentenceCount", count).toString())
        return record
    }

    private fun sentenceText(index: Int) = "第${index + 1}句，溪水静静流过山谷。"
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000.0
    private fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
