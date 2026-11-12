package com.freereading.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceTimelineTest {
    private val durations = listOf(10_000L, 8_000L, 12_000L, 3_000L)
    private fun seek(index: Int, position: Long, delta: Long) = runBlocking {
        SentenceTimeline.seek(durations.size, index, position, delta) { durations[it] }
    }

    @Test fun forwardCrossesMultipleSentenceFilesUsingRealDurations() {
        assertEquals(SentencePosition(2, 4_000), seek(0, 7_000, 15_000))
    }
    @Test fun backwardCrossesMultipleSentenceFiles() {
        assertEquals(SentencePosition(0, 5_000), seek(2, 2_000, -15_000))
    }
    @Test fun boundaryMovesForwardToNextSentenceStart() {
        assertEquals(SentencePosition(1, 0), seek(0, 8_000, 2_000))
    }
    @Test fun beforeBookStartAndAfterBookEndAreClamped() {
        assertEquals(SentencePosition(0, 0), seek(1, 1_000, -100_000))
        assertEquals(SentencePosition(3, 3_000), seek(2, 11_000, 100_000))
    }
    @Test fun oldOffsetBeyondChangedVoiceDurationIsClamped() {
        assertEquals(SentencePosition(1, 0), seek(0, 99_000, 0))
    }
    @Test fun loadsDurationsForEveryCrossedUnpreparedSentence() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = SentenceTimeline.seek(4, 0, 7_000, 15_000) {
            requested += it
            durations[it]
        }
        assertEquals(SentencePosition(2, 4_000), result)
        assertEquals(listOf(0, 1, 2), requested)
    }

    @Test fun twoForwardPressesBeforeSynthesisCompletesAdvanceThirtySeconds() = runBlocking {
        val accumulator = RelativeSeekAccumulator()
        accumulator.enqueue(SentencePosition(0, 7_000), 15_000)
        val second = accumulator.enqueue(SentencePosition(0, 7_000), 15_000)
        val target = SentenceTimeline.seek(3, second.anchor.index,
            second.anchor.positionMs, second.deltaMs) { 20_000L }
        assertEquals(SentencePosition(1, 17_000), target)
    }

    @Test fun completionOfCancelledSeekCannotClearTheFollowingRequest() {
        val accumulator = RelativeSeekAccumulator()
        val first = accumulator.enqueue(SentencePosition(0, 7_000), 15_000)
        accumulator.enqueue(SentencePosition(0, 7_000), 15_000)
        accumulator.complete(first)
        val third = accumulator.enqueue(SentencePosition(0, 7_000), -15_000)
        assertEquals(15_000L, third.deltaMs)
        assertEquals(SentencePosition(0, 7_000), third.anchor)
        accumulator.complete(third)
        val fresh = accumulator.enqueue(SentencePosition(2, 1_000), 15_000)
        assertEquals(SentencePosition(2, 1_000), fresh.anchor)
        assertEquals(15_000L, fresh.deltaMs)
    }

    @Test fun absoluteNavigationClearsPreviousRelativeSeekAnchor() {
        val accumulator = RelativeSeekAccumulator()
        accumulator.enqueue(SentencePosition(0, 7_000), 15_000)
        accumulator.clear()
        val fresh = accumulator.enqueue(SentencePosition(5, 0), -15_000)
        assertEquals(SentencePosition(5, 0), fresh.anchor)
        assertEquals(-15_000L, fresh.deltaMs)
    }
}
