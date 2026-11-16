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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpeechSentenceAnchorsTest {
    @Test fun everySourceRangeBoundaryMatchesFullReadiumContext() {
        val text = " \t甲\r\n乙\u000b\u000c 丙\u00a0丁\u2003😀戊。\n "
        val source = source(text, before = "\u00a0前\t \n", after = "\r\n后\u2003")
        val boundaries = (0..text.length).filter { offset ->
            offset == 0 || offset == text.length ||
                !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
        }
        val sentences = buildList {
            for (start in boundaries) for (end in boundaries) {
                if (end > start) add(SpeechSentence(size, source, start, end))
            }
        }

        val anchors = SpeechSentenceAnchors.build(sentences.asSequence())

        sentences.zip(anchors).forEach { (sentence, anchor) -> assertEquivalent(sentence, anchor) }
    }

    @Test fun longAsciiWhitespaceRunsAreCollapsedBeforeTheContextLimit() {
        val spaces = " \t\r\n\u000b\u000c".repeat(2_000)
        val source = source("前".repeat(30) + spaces + "近".repeat(140) + "目标。" +
            "后".repeat(140) + spaces + "尾".repeat(30))
        val start = source.text.indexOf("目标。")
        val sentence = SpeechSentence(7, source, start, start + 3)

        val anchor = SpeechSentenceAnchors.build(sequenceOf(sentence)).single()

        assertEquivalent(sentence, anchor)
        assertEquals("前".repeat(19) + " " + "近".repeat(140), normalize(anchor.before).takeLast(160))
        assertEquals("后".repeat(140) + " " + "尾".repeat(19), normalize(anchor.after).take(160))
    }

    @Test fun aContextCutOnAsciiOrUnicodeWhitespaceSurvivesMatchersSecondTrim() {
        for ((before, after) in listOf(
            ("起 " + "前".repeat(159)) to ("后".repeat(159) + " 终"),
            ("起" + "\u00a0".repeat(10) + "前".repeat(150)) to
                ("后".repeat(150) + "\u2003".repeat(10) + "终"),
        )) {
            val sentence = SpeechSentence(0, source("目标。", before, after), 0, 3)

            val anchor = SpeechSentenceAnchors.build(sequenceOf(sentence)).single()

            assertEquivalent(sentence, anchor)
            assertTrue(anchor.before.length <= 161 && anchor.after.length <= 161)
            if (before.startsWith("起 ")) {
                assertEquals(161, anchor.before.length)
                assertEquals(161, anchor.after.length)
                assertTrue(normalize(anchor.before).takeLast(160).first().isWhitespace())
                assertTrue(normalize(anchor.after).take(160).last().isWhitespace())
            }
        }
    }

    @Test fun emojiAtTheWindowBoundaryRetainsMatchersUtf16TakeSemantics() {
        val sentence = SpeechSentence(0, source("目标。",
            before = "甲😀" + "前".repeat(159),
            after = "后".repeat(159) + "😀乙"), 0, 3)

        val anchor = SpeechSentenceAnchors.build(sequenceOf(sentence)).single()

        assertEquivalent(sentence, anchor)
        assertTrue(Character.isLowSurrogate(normalize(anchor.before).takeLast(160).first()))
        assertTrue(Character.isHighSurrogate(normalize(anchor.after).take(160).last()))
    }

    @Test fun repeatedSentenceAndPartialSelectionKeepTheExistingMatchingResult() {
        val text = "第一天，" + "甲".repeat(170) + "他终于回来了。屋里很静。" +
            "第三天，" + "乙".repeat(170) + "他终于回来了。雨停了。"
        val source = source(text)
        val first = text.indexOf("他终于回来了。")
        val second = text.indexOf("他终于回来了。", first + 1)
        val sentences = listOf(first, second).mapIndexed { index, start ->
            SpeechSentence(index, source, start, start + "他终于回来了。".length)
        }
        val full = sentences.map(::fullAnchor)
        val compact = SpeechSentenceAnchors.build(sentences.asSequence())
        val target = SentenceLocatorMatcher.Anchor(-1, "#paragraph", "第三天，" +
            "乙".repeat(170) + "他终于", "回来了", "。雨停了。")

        assertEquals(1, SentenceLocatorMatcher.find(full, target))
        assertEquals(SentenceLocatorMatcher.find(full, target), SentenceLocatorMatcher.find(compact, target))
        assertEquals(SentenceLocatorMatcher.find(full, full[1]), SentenceLocatorMatcher.find(compact, full[1]))
    }

    @Test fun aFullAllowedBlockBuildsBoundedAnchorsWithoutExpandingAllSentenceLocators() {
        val source = source("甲。".repeat(32_768))
        val sentences = List(32_768) { index -> SpeechSentence(index, source, index * 2, index * 2 + 2) }

        val anchors = SpeechSentenceAnchors.build(sentences.asSequence())

        assertEquals(32_768, anchors.size)
        assertTrue(anchors.all { it.before.length <= 161 && it.after.length <= 161 && it.text == "甲。" })
        for (index in listOf(0, 79, 80, 16_384, 32_767)) assertEquivalent(sentences[index], anchors[index])
    }

    @Test fun compactGuardsNeverChangeTheActualNavigationLocator() {
        val before = "起 " + "前".repeat(159)
        val after = "后".repeat(159) + " 终"
        val sentence = SpeechSentence(0, source("目标。", before, after), 0, 3)
        val original = sentence.locatorJson

        SpeechSentenceAnchors.build(sequenceOf(sentence))

        assertEquals(original, sentence.locatorJson)
        val locator = requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson)))
        assertEquals(before, locator.text.before)
        assertEquals(after, locator.text.after)
    }

    @Test fun legacySentencesStillParseTheirStoredLocatorAndSkipInvalidJson() {
        val locator = locator("原始文字。", "前文".repeat(100), "后文".repeat(100))
        val legacy = SpeechSentence(17, "旧版朗读文本", locator.toJSON().toString(), 3, "原创章节")
        val invalid = SpeechSentence(18, "损坏", "invalid-json", 3, "原创章节")

        val anchor = SpeechSentenceAnchors.build(sequenceOf(legacy, invalid)).single()

        assertEquivalent(legacy, anchor)
        assertEquals(17, anchor.index)
        assertEquals("原始文字。", anchor.text)
        assertTrue(anchor.before.length <= 161 && anchor.after.length <= 161)
    }

    @Test fun emptyInputAndEntirelyWhitespaceContextsRemainEmpty() {
        assertTrue(SpeechSentenceAnchors.build(emptySequence()).isEmpty())
        val sentence = SpeechSentence(0, source(" \t\n", "\u00a0 \r", "\t\u2003"), 0, 3)

        val anchor = SpeechSentenceAnchors.build(sequenceOf(sentence)).single()

        assertEquivalent(sentence, anchor)
        assertEquals("", anchor.before)
        assertEquals("", anchor.after)
    }

    private fun assertEquivalent(sentence: SpeechSentence, actual: SentenceLocatorMatcher.Anchor) {
        val full = fullAnchor(sentence)
        assertEquals(full.index, actual.index)
        assertEquals(full.selector, actual.selector)
        assertEquals(full.text, actual.text)
        assertEquals("before ${sentence.index}", normalize(full.before).takeLast(160), normalize(actual.before).takeLast(160))
        assertEquals("after ${sentence.index}", normalize(full.after).take(160), normalize(actual.after).take(160))
    }

    private fun fullAnchor(sentence: SpeechSentence): SentenceLocatorMatcher.Anchor {
        val locator = requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson)))
        return SentenceLocatorMatcher.Anchor(sentence.index, locator.locations["cssSelector"] as? String,
            locator.text.before.orEmpty(), locator.text.highlight.orEmpty(), locator.text.after.orEmpty())
    }

    private fun source(text: String, before: String = "", after: String = "") =
        SpeechSource(locator(text, before, after), 3, "原创章节")

    private fun locator(text: String, before: String, after: String): Locator = requireNotNull(
        Locator.fromJSON(JSONObject().apply {
            put("href", "chapter.xhtml")
            put("type", "application/xhtml+xml")
            put("locations", JSONObject().put("cssSelector", "#paragraph"))
            put("text", JSONObject().put("before", before).put("highlight", text).put("after", after))
        })
    )

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
