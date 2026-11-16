package com.freereading.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SpeechIndexProgressTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun countsSharedSourcesAndRangesAndFinishesOnlyAfterAtomicCommit() = runBlocking {
        val file = File(temporary.root, "speech.json")
        val events = ArrayList<Pair<Long, Long?>>()
        SpeechIndexStore().write(file, book()) { completed, total ->
            events.add(completed to total)
            if (total != null && completed == total) {
                assertTrue(file.isFile)
                assertEquals(3, JSONObject(file.readText()).getJSONArray("sentences").length())
                assertFalse(File(file.path + ".bak").exists())
            }
        }

        assertEquals(listOf(0L to null, 0L to 5L, 1L to 5L, 2L to 5L,
            3L to 5L, 4L to 5L, 5L to 5L), events)
    }

    @Test fun byteLimitFailureNeverReportsTheSaveAsComplete() = runBlocking {
        val file = File(temporary.root, "speech.json")
        val events = ArrayList<Pair<Long, Long?>>()
        try {
            SpeechIndexStore(100).write(file, book()) { done, total -> events.add(done to total) }
            fail("Expected the byte limit to fail")
        } catch (_: IOException) { }
        assertTrue(events.isNotEmpty())
        assertTrue(events.none { (done, total) -> total != null && done >= total })
        assertFalse(file.exists())
    }

    private fun book(): SpeechBook {
        val first = source("甲。乙。")
        val second = source("丙。")
        val sentences = listOf(SpeechSentence(0, first, 0, 2), SpeechSentence(1, first, 2, 4),
            SpeechSentence(2, second, 0, 2))
        return SpeechBook(BookRecord("a".repeat(64), "原创书", "作者", "zh", 0, 3), sentences)
    }

    private fun source(text: String): SpeechSource {
        val locator = Locator.fromJSON(JSONObject().put("href", "chapter.xhtml")
            .put("type", "application/xhtml+xml").put("text", JSONObject().put("highlight", text)))!!
        return SpeechSource(locator, 0, "原创章节")
    }
}
