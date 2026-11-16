package com.freereading.reader

import android.app.Application
import com.freereading.data.SpeechSentence
import com.freereading.data.SpeechSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run with -PunitTestMaxHeapSize=256m to exercise a whole legal chapter near the sentence cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpeechSentenceAnchorsCapacityTest {
    @Test fun oneChapterNearTheSentenceCapBuildsAndResolvesItsAnchorsWithinBoundedMemory() {
        val paragraph = "甲。".repeat(32_768)
        val sentences = ArrayList<SpeechSentence>(491_520)
        repeat(15) { block ->
            val locator = requireNotNull(Locator.fromJSON(JSONObject().apply {
                put("href", "chapter.xhtml")
                put("type", "application/xhtml+xml")
                put("locations", JSONObject().put("cssSelector", "#paragraph-$block"))
                put("text", JSONObject().put("highlight", paragraph))
            }))
            val source = SpeechSource(locator, 0, "原创大章")
            repeat(32_768) { offset ->
                sentences.add(SpeechSentence(sentences.size, source, offset * 2, offset * 2 + 2))
            }
        }

        val anchors = SpeechSentenceAnchors.build(sentences.asSequence())

        assertEquals(491_520, anchors.size)
        // Resolve a real, unambiguous sentence in the last block without holding every context.
        val target = SentenceLocatorMatcher.Anchor(-1, "#paragraph-14", "", "甲。", "甲。".repeat(100))
        assertEquals(458_752, SentenceLocatorMatcher.find(anchors, target))
        val wholeParagraph = SentenceLocatorMatcher.Anchor(-1, "#paragraph-14", "", paragraph, "")
        assertEquals(458_752, SentenceLocatorMatcher.find(anchors, wholeParagraph))
        for (index in listOf(0, 32_767, 32_768, 458_752, 491_519)) {
            assertEquals(sentences[index].index, anchors[index].index)
            assertEquals("甲。", anchors[index].text)
            assertTrue(anchors[index].before.length <= 161 && anchors[index].after.length <= 161)
        }
    }
}
