package com.freereading.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    @Test fun reopeningAudioWithZeroPositionAndDurationReturnsZeroRatherThanNaN() {
        val reopening = PlaybackSnapshot(positionMs = 0, durationMs = 0, isPreparing = true)
        assertEquals(0f, reopening.progressFraction, 0f)
        assertTrue(reopening.progressFraction.isFinite())
    }

    @Test fun positivePositionWithUnknownOrNegativeDurationStillReturnsZero() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(0f, PlaybackSnapshot(positionMs = 5_000, durationMs = duration).progressFraction, 0f)
        }
    }

    @Test fun negativePositionsAreClampedToZero() {
        for (position in listOf(-1L, Long.MIN_VALUE)) {
            assertEquals(0f, PlaybackSnapshot(positionMs = position, durationMs = 10_000).progressFraction, 0f)
        }
    }

    @Test fun aPositionBeyondTheDurationIsClampedToOne() {
        assertEquals(1f, PlaybackSnapshot(positionMs = 15_000, durationMs = 10_000).progressFraction, 0f)
        assertEquals(1f, PlaybackSnapshot(positionMs = Long.MAX_VALUE, durationMs = 1).progressFraction, 0f)
    }

    @Test fun largeLongValuesRemainFiniteAndKeepTheExpectedFraction() {
        val half = PlaybackSnapshot(positionMs = Long.MAX_VALUE / 2, durationMs = Long.MAX_VALUE).progressFraction
        assertEquals(0.5f, half, 0.000001f)
        assertTrue(half.isFinite())
        assertEquals(1f, PlaybackSnapshot(positionMs = Long.MAX_VALUE, durationMs = Long.MAX_VALUE).progressFraction, 0f)
    }

    @Test fun aValidWithinSentencePositionReturnsItsActualFraction() {
        assertEquals(0.25f, PlaybackSnapshot(positionMs = 2_500, durationMs = 10_000).progressFraction, 0f)
    }
}
