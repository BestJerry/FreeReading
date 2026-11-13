package com.freereading.audio

import com.freereading.data.SpeechSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterProgressTest {
    private fun sentence(index: Int, text: String, chapter: Int = 0, title: String = "本章") =
        SpeechSentence(index, text, "{}", chapter, title)

    private fun unequalSentences() = ChapterProgressIndex(listOf(
        sentence(0, "甲"), sentence(1, "乙".repeat(9)),
    ))

    @Test fun progressWeightsBodyTextRatherThanGivingEverySentenceTheSameWeight() {
        val progress = unequalSentences()
        assertEquals(ChapterProgress(2, 0f), progress.at(0, 0, 1_000))
        assertEquals(0.05f, progress.at(0, 500, 1_000).fraction, 0.000001f)
        assertEquals(0.1f, progress.at(1, 0, 1_000).fraction, 0.000001f)
        assertEquals(0.55f, progress.at(1, 500, 1_000).fraction, 0.000001f)
        assertEquals(1f, progress.at(1, 1_000, 1_000).fraction, 0f)
    }

    @Test fun beginningAndEndOfAChapterAreExactlyZeroAndOne() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "第一句。"), sentence(1, "第二句。")))
        assertEquals(0f, progress.at(0, 0, 1_634).fraction, 0f)
        assertEquals(1f, progress.at(1, 2_059, 2_059).fraction, 0f)
    }

    @Test fun knownClipEndsAndUnknownNextClipStartsAreContinuous() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲乙"), sentence(1, "丙".repeat(7)), sentence(2, "丁戊己")))
        assertEquals(progress.at(0, 1_990, 1_990).fraction, progress.at(1, 0, 0).fraction, 0f)
        assertEquals(progress.at(1, 1_634, 1_634).fraction, progress.at(2, 0, 2_059).fraction, 0f)
        assertEquals(0.75f, progress.at(2, 0, 0).fraction, 0f)
    }

    @Test fun currentSentenceUsesItsActualMilliseconds() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲甲"), sentence(1, "乙乙乙乙乙乙")))
        assertEquals(0.625f, progress.at(1, 1_029, 2_058).fraction, 0f)
    }

    @Test fun supplementaryEmojiCountAsOneCodePointInsteadOfTwoUtf16Units() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲😀乙"), sentence(1, "丙")))
        assertEquals(0.75f, progress.at(0, 1_000, 1_000).fraction, 0f)
        assertEquals(0.875f, progress.at(1, 500, 1_000).fraction, 0f)
    }

    @Test fun joinedEmojiUseUnicodeCodePointsRatherThanGraphemeCount() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "👩‍💻"), sentence(1, "甲")))
        assertEquals(0.75f, progress.at(0, 1, 1).fraction, 0f)
    }

    @Test fun punctuationAndWhitespaceRemainPartOfTheSourceTextWeight() {
        val progress = ChapterProgressIndex(listOf(sentence(0, " \n"), sentence(1, "甲，乙。")))
        assertEquals(1f / 3f, progress.at(0, 1, 1).fraction, 0.000001f)
    }

    @Test fun emptyTextHasMinimumWeightAndSingleSentenceChapterStillWorks() {
        val single = ChapterProgressIndex(listOf(sentence(0, "")))
        assertEquals(ChapterProgress(1, 0f), single.at(0, 0, 1_000))
        assertEquals(0.25f, single.at(0, 250, 1_000).fraction, 0f)
        assertEquals(1f, single.at(0, 1_000, 1_000).fraction, 0f)
        val multiple = ChapterProgressIndex(listOf(sentence(0, ""), sentence(1, "甲，乙。")))
        assertEquals(0.2f, multiple.at(0, 1, 1).fraction, 0.000001f)
    }

    @Test fun repeatedTitlesDoNotMergeDifferentResources() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲", 3, "同名"), sentence(1, "乙", 8, "同名")))
        assertEquals(ChapterProgress(1, 0.5f), progress.at(0, 500, 1_000))
        assertEquals(ChapterProgress(1, 0.5f), progress.at(1, 500, 1_000))
    }

    @Test fun differentTitlesWithinTheSameResourceDoNotSplitAChapter() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲", 3, "第一节"), sentence(1, "乙", 3, "第二节")))
        assertEquals(ChapterProgress(2, 0.5f), progress.at(1, 0, 1_000))
    }

    @Test fun sameResourceIdInNonContiguousRunsIsNotMerged() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲", 2), sentence(1, "乙", 2),
            sentence(2, "丙", 5), sentence(3, "丁", 2), sentence(4, "戊", 2)))
        assertEquals(ChapterProgress(2, 1f), progress.at(1, 1_000, 1_000))
        assertEquals(ChapterProgress(2, 0f), progress.at(3, 0, 1_000))
        assertEquals(ChapterProgress(2, 0.5f), progress.at(4, 0, 1_000))
    }

    @Test fun missingEmptyResourceIdsDoNotCreatePhantomSentences() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲", 1), sentence(1, "乙", 1),
            sentence(2, "丙", 9), sentence(3, "丁", 9)))
        assertEquals(ChapterProgress(2, 1f), progress.at(1, 1_000, 1_000))
        assertEquals(ChapterProgress(2, 0f), progress.at(2, 0, 1_000))
    }

    @Test fun zeroAndNegativeDurationsOnlyCountTheCompletedSentences() {
        val progress = unequalSentences()
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(0f, progress.at(0, Long.MAX_VALUE, duration).fraction, 0f)
            assertEquals(0.1f, progress.at(1, Long.MAX_VALUE, duration).fraction, 0.000001f)
        }
    }

    @Test fun negativePositionsAndOverrunPositionsAreClampedWithinTheCurrentSentence() {
        val progress = unequalSentences()
        assertEquals(0f, progress.at(0, Long.MIN_VALUE, 1_000).fraction, 0f)
        assertEquals(0.1f, progress.at(1, Long.MIN_VALUE, 1_000).fraction, 0.000001f)
        assertEquals(0.1f, progress.at(0, Long.MAX_VALUE, 1_000).fraction, 0.000001f)
        assertEquals(1f, progress.at(1, Long.MAX_VALUE, 1_000).fraction, 0f)
    }

    @Test fun hugeLongMediaTimesStayFiniteAndDoNotOverflowAnIntermediateProduct() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲"), sentence(1, "乙乙乙")))
        assertEquals(0.625f, progress.at(1, Long.MAX_VALUE / 2, Long.MAX_VALUE).fraction, 0.000001f)
        assertEquals(1f, progress.at(1, Long.MAX_VALUE, Long.MAX_VALUE).fraction, 0f)
        for (position in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE / 2, Long.MAX_VALUE)) {
            val value = progress.at(1, position, Long.MAX_VALUE).fraction
            assertTrue(value.isFinite() && value in 0f..1f)
        }
    }

    @Test fun queriesDoNotRetainOrReReadTheInputBookObjects() {
        val sentences = mutableListOf(sentence(0, "甲"), sentence(1, "乙乙乙"))
        val progress = ChapterProgressIndex(sentences)
        sentences[0] = sentence(0, "别的正文", 99)
        sentences.clear()
        assertEquals(ChapterProgress(2, 0.25f), progress.at(0, 1_000, 1_000))
        assertEquals(ChapterProgress(2, 0.625f), progress.at(1, 500, 1_000))
    }

    @Test fun emptyBooksAndInvalidSentenceIndicesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChapterProgressIndex(emptyList()).at(0, 0, 0) }
        val progress = unequalSentences()
        for (index in listOf(Int.MIN_VALUE, -1, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { progress.at(index, 0, 1_000) }
        }
    }

    @Test fun knownAudioProgressIsMonotonicAcrossUnequalSentences() {
        val progress = ChapterProgressIndex(listOf(sentence(0, "甲"), sentence(1, "乙乙乙乙"), sentence(2, "丙丙")))
        var previous = 0f
        for (index in 0..2) {
            for (position in listOf(0L, 1L, 250L, 700L, 1_000L)) {
                val current = progress.at(index, position, 1_000).fraction
                assertTrue(current.isFinite() && current >= previous && current in 0f..1f)
                previous = current
            }
        }
        assertEquals(1f, previous, 0f)
    }
}
