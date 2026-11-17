package com.freereading.epub

import com.freereading.ReadingApplication
import com.freereading.data.OriginalEpubFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local diagnosis only: reports stages, without a device-time claim or flaky wall-time budget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReadiumColdOpenDiagnosticTest {
    @Test fun recordsWholeBookValidationAndPositionPreparationOnAFreshEngine() = profile(reading = false)

    @Test fun recordsPreparedReadingFastPathOnAFreshEngine() = profile(reading = true)

    private fun profile(reading: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val work = File(app.cacheDir, "readium-cold-open-diagnostic").apply { mkdirs() }
        val supplied = System.getenv("FREEREADING_PERF_EPUB")?.takeIf(String::isNotBlank)?.let(::File)
        val file = supplied ?: OriginalEpubFixture.numberedChapters(File(work, "original-long.epub"),
            totalSentences = 350_001, sentencesPerChapter = 125)
        try {
            val engine = ReadiumEngine(app)
            val start = System.nanoTime()
            var fullValidationStart = 0L
            var totalResources = -1L
            var resourcesRead = 0L
            val publication = if (reading) engine.openForReading(file) else {
                engine.open(file) { done, total ->
                    if (done == 0L && total != null) {
                        fullValidationStart = System.nanoTime()
                        totalResources = total
                    }
                    resourcesRead = done
                }
            }
            val opened = System.nanoTime()
            try {
                val positions = publication.positionsByReadingOrder()
                val positionsReady = System.nanoTime()
                val cachedPositions = publication.positionsByReadingOrder()
                val cachedReady = System.nanoTime()
                fun ms(begin: Long, end: Long) = (end - begin) / 1_000_000.0
                println("FREEREADING_COLD_OPEN " + JSONObject().apply {
                    put("input", if (supplied == null) "original-350001-sentences" else "supplied-local-epub")
                    put("mode", if (reading) "reading" else "strict-import")
                    put("compressedBytes", file.length())
                    put("readingOrderResources", publication.readingOrder.size)
                    put("validatedResources", resourcesRead)
                    if (!reading) {
                        put("parseAndArchiveMs", ms(start, fullValidationStart))
                        put("wholeBookValidationMs", ms(fullValidationStart, opened))
                    }
                    put("openMs", ms(start, opened))
                    put("positionsColdMs", ms(opened, positionsReady))
                    put("positionsWarmMs", ms(positionsReady, cachedReady))
                    put("positionsCount", positions.sumOf { it.size })
                })
                if (!reading) {
                    assertEquals(publication.readingOrder.size.toLong(), totalResources)
                    assertEquals(totalResources, resourcesRead)
                    assertTrue(fullValidationStart > start)
                }
                assertTrue(positions.all { it.isNotEmpty() })
                assertSame(positions, cachedPositions)
            } finally {
                publication.close()
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
