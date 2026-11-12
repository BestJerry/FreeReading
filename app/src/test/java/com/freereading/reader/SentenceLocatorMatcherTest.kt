package com.freereading.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceLocatorMatcherTest {
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
