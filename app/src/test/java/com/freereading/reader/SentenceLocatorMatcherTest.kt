package com.freereading.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceLocatorMatcherTest {
    @Test fun aWholeRepeatedParagraphStartsAtItsFirstSentenceInsteadOfTheMiddle() {
        val candidates = List(200) { index ->
            SentenceLocatorMatcher.Anchor(index, "#paragraph", "甲。".repeat(index), "甲。",
                "甲。".repeat(199 - index))
        }
        val paragraph = SentenceLocatorMatcher.Anchor(-1, "#paragraph", "", "甲。".repeat(200), "")

        assertEquals(0, SentenceLocatorMatcher.find(candidates, paragraph))
    }

    private fun anchor(
        index: Int,
        text: String,
        before: String = "",
        after: String = "",
        selector: String? = "#paragraph",
    ) = SentenceLocatorMatcher.Anchor(index, selector, before, text, after)

    @Test fun repeatedSentenceUsesSurroundingText() {
        val candidates = listOf(
            anchor(0, "他回来了。", before = "第一天，", after = "屋里很静。"),
            anchor(1, "他回来了。", before = "第三天，", after = "雨停了。"),
        )
        assertEquals(1, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "他回来了。", before = "第三天，", after = "雨停了。")))
    }

    @Test fun selectionUsesTextRatherThanVisiblePageSelector() {
        val candidates = listOf(
            anchor(0, "首页中的文字。", selector = "#first"),
            anchor(1, "真正选中的文字。", selector = "#second"),
        )
        assertEquals(1, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "选中的文字", selector = "#first")))
    }

    @Test fun partialSelectionInRepeatedSentenceUsesSelectionRelativeContext() {
        val candidates = listOf(
            anchor(0, "他终于回来了。", before = "第一天，", after = "屋里很静。"),
            anchor(1, "他终于回来了。", before = "第三天，", after = "雨停了。"),
        )
        assertEquals(1, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "回来了", before = "第三天，他终于", after = "。雨停了。")))
    }

    @Test fun partialSelectionStillChecksLaterOccurrencesWithinOneSentence() {
        val candidates = listOf(
            anchor(0, "XtermYtermZ"),
            anchor(1, "YtermQ"),
        )

        assertEquals(0, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "term", before = "Y", after = "Z")))
    }

    @Test fun spanningContextIncludesOutsideTextWhenTheTargetPrefixAndSuffixAreShort() {
        val candidates = listOf(
            anchor(0, "开始。", before = "其他前文", after = "其他后文"),
            anchor(1, "开始。", before = "段外前文前言", after = "尾声段外后文"),
        )

        assertEquals(1, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "前言开始。尾声", before = "段外前文", after = "段外后文")))
    }

    @Test fun firstVisibleParagraphStartsAtFirstSentence() {
        val candidates = listOf(
            anchor(3, "第一句。", after = "第二句。"),
            anchor(4, "第二句。", before = "第一句。"),
        )
        assertEquals(3, SentenceLocatorMatcher.find(candidates,
            anchor(-1, "第一句。第二句。")))
    }

    @Test fun whitespaceDifferencesDoNotChangeSentence() {
        assertEquals(2, SentenceLocatorMatcher.find(listOf(anchor(2, "a\n b。")),
            anchor(-1, "a b。")))
    }

    @Test fun emptyChapterHasNoSpeechTarget() {
        assertNull(SentenceLocatorMatcher.find(emptyList(), anchor(-1, "")))
    }

    @Test fun pageLocationWithoutTextAnchorCannotGuessSentence() {
        assertNull(SentenceLocatorMatcher.find(listOf(anchor(0, "正文。")),
            anchor(-1, "", selector = null)))
    }

    @Test fun unknownElementDoesNotFallBackToChapterStart() {
        assertNull(SentenceLocatorMatcher.find(listOf(anchor(0, "正文。", selector = "#first")),
            anchor(-1, "", selector = "#missing")))
    }
}
