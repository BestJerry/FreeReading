package com.freereading.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceRangesTest {
    @Test fun chineseEndingsKeepClosingQuotesAndEllipses() {
        val text = "他说：“你好！”然后离开……她问：真的？是的。"
        assertEquals(
            listOf("他说：“你好！”", "然后离开……", "她问：真的？", "是的。"),
            SentenceRanges.split(text).map { text.substring(it) },
        )
    }

    @Test fun decimalAndNewlinesPreserveSourceRanges() {
        val text = "  Version 3.14 is ready.\n下一行。  "
        val ranges = SentenceRanges.split(text)
        assertEquals(listOf("Version 3.14 is ready.", "下一行。"), ranges.map { text.substring(it) })
        assertEquals(2, ranges.first().first)
    }

    @Test fun longTextNeverSplitsSurrogatePairsOrLosesCharacters() {
        val text = "甲😀乙".repeat(400)
        val chunks = SentenceRanges.split(text, maxLength = 31).map { text.substring(it) }
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 31 })
        assertFalse(chunks.any { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test fun repeatedSentencesRetainDifferentOffsets() {
        val text = "重复。重复。重复。"
        assertEquals(listOf(0..2, 3..5, 6..8), SentenceRanges.split(text))
    }

    @Test fun emptyAndPunctuationOnlyContentAreNotSpoken() {
        assertTrue(SentenceRanges.split("  \n……！！！ ").isEmpty())
        assertTrue(SentenceRanges.split("").isEmpty())
    }
}
