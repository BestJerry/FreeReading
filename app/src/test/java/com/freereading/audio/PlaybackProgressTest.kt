package com.freereading.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    @Test fun preparingWithoutAudioDurationHasFiniteZeroProgress() {
        val preparing = PlaybackSnapshot(isPreparing = true)
        assertEquals(0f, preparing.chapterProgressFraction, 0f)
        assertTrue(preparing.chapterProgressFraction.isFinite())
    }

    @Test fun invalidFractionsCannotReachProgressBarSemantics() {
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, PlaybackSnapshot(chapterProgress = fraction).chapterProgressFraction, 0f)
        }
    }

    @Test fun negativeChapterProgressIsClampedToZero() {
        assertEquals(0f, PlaybackSnapshot(chapterProgress = -0.1f).chapterProgressFraction, 0f)
    }

    @Test fun overshootingChapterProgressIsClampedToOne() {
        assertEquals(1f, PlaybackSnapshot(chapterProgress = 1.1f).chapterProgressFraction, 0f)
    }

    @Test fun validChapterFractionIsPreserved() {
        assertEquals(0.25f, PlaybackSnapshot(chapterProgress = 0.25f).chapterProgressFraction, 0f)
    }

    @Test fun sentenceBoundaryDoesNotReplaceChapterProgressWithSentenceProgress() {
        val endOfFirst = PlaybackSnapshot(positionMs = 2_000, durationMs = 2_000, chapterProgress = 0.25f)
        val startOfSecond = endOfFirst.copy(positionMs = 0, durationMs = 6_000)
        assertEquals(0.25f, endOfFirst.chapterProgressFraction, 0f)
        assertEquals(endOfFirst.chapterProgressFraction, startOfSecond.chapterProgressFraction, 0f)
    }

    @Test fun speedDoesNotChangeTextPositionProgress() {
        val normal = PlaybackSnapshot(chapterProgress = 0.25f, speed = 1f)
        assertEquals(normal.chapterProgressFraction, normal.copy(speed = 2f).chapterProgressFraction, 0f)
    }
}
