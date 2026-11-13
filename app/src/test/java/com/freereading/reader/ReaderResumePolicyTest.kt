package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot
import com.freereading.data.BookRecord
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderResumePolicyTest {
    private val book = SpeechBook(
        BookRecord("reading-book", "原创测试书", "作者", "zh", 0, 3),
        List(3) { index -> SpeechSentence(index, "测试句子$index。", "locator-$index", 0, "第一章") },
    )
    private val persisted = ReadingProgress(
        bookId = book.book.id,
        locatorJson = "precise-visual-page",
        sentenceIndex = 1,
        audioOffsetMs = 1_000,
        updatedAt = 10,
    )
    private val snapshot = PlaybackSnapshot(
        bookId = book.book.id,
        sentenceIndex = 1,
        positionMs = 6_000,
    )

    @Test fun stoppingBeforeTheDocumentLoadsStillCapturesTheSentenceAndOffset() {
        val stopped = stoppedPlaybackProgress(book.book.id, snapshot, sentences = null)!!

        assertEquals(book.book.id, stopped.bookId)
        assertEquals(1, stopped.sentenceIndex)
        assertEquals(6_000L, stopped.audioOffsetMs)
        assertNull(stopped.locatorJson)
    }

    @Test fun stoppingWithLoadedSentencesIncludesItsCanonicalLocator() {
        val stopped = stoppedPlaybackProgress(book.book.id, snapshot, book.sentences)!!

        assertEquals("locator-1", stopped.locatorJson)
        assertEquals(6_000L, stopped.audioOffsetMs)
    }

    @Test fun stoppingDoesNotCaptureAnotherBookOrAnEmptyHub() {
        assertNull(stoppedPlaybackProgress(book.book.id, snapshot.copy(bookId = "another-book"), book.sentences))
        assertNull(stoppedPlaybackProgress(book.book.id, PlaybackSnapshot(), book.sentences))
    }

    @Test fun capturedNegativeOffsetsAreClampedWithoutRequiringASentenceLocator() {
        val stopped = stoppedPlaybackProgress(book.book.id,
            snapshot.copy(sentenceIndex = Int.MAX_VALUE, positionMs = -1), book.sentences)!!

        assertEquals(Int.MAX_VALUE, stopped.sentenceIndex)
        assertEquals(0L, stopped.audioOffsetMs)
        assertNull(stopped.locatorJson)
    }

    @Test fun stopAtSixSecondsWinsOverTheOneSecondProgressReadBeforeLoading() {
        val stopped = stoppedPlaybackProgress(book.book.id, snapshot, sentences = null)

        val resumed = resolve(stopped = stopped)!!

        assertEquals(1, resumed.sentenceIndex)
        assertEquals(6_000L, resumed.audioOffsetMs)
        assertEquals("locator-1", resumed.locatorJson)
        assertEquals(stopped!!.updatedAt, resumed.updatedAt)
    }

    @Test fun stoppedProgressWinsBeforeTheServiceHasClearedItsHubBookId() {
        val stopped = stoppedPlaybackProgress(book.book.id, snapshot, sentences = null)

        val resumed = resolve(stopped = stopped, activeBookId = book.book.id)!!

        assertEquals(6_000L, resumed.audioOffsetMs)
        assertEquals("locator-1", resumed.locatorJson)
    }

    @Test fun stoppedProgressUsesTheLoadedCanonicalLocatorInsteadOfAStaleLocator() {
        val resumed = resolve(stopped = persisted.copy(locatorJson = "stale", audioOffsetMs = 6_000))!!

        assertEquals("locator-1", resumed.locatorJson)
        assertEquals(6_000L, resumed.audioOffsetMs)
    }

    @Test fun stoppedSentenceIndicesAreClampedToTheLoadedBook() {
        val before = resolve(stopped = persisted.copy(sentenceIndex = Int.MIN_VALUE, audioOffsetMs = -3))!!
        val after = resolve(stopped = persisted.copy(sentenceIndex = Int.MAX_VALUE, audioOffsetMs = 6_000))!!

        assertEquals(0, before.sentenceIndex)
        assertEquals("locator-0", before.locatorJson)
        assertEquals(0L, before.audioOffsetMs)
        assertEquals(2, after.sentenceIndex)
        assertEquals("locator-2", after.locatorJson)
        assertEquals(6_000L, after.audioOffsetMs)
    }

    @Test fun manualReadingNavigationTakesPriorityOverStoppedOrPersistedAudio() {
        assertNull(resolve(stopped = persisted, activeBookId = book.book.id,
            intent = ReadingPlaybackIntent.FROM_READING_LOCATION))
        assertNull(resolve(intent = ReadingPlaybackIntent.FROM_READING_LOCATION))
    }

    @Test fun anActiveSameBookKeepsItsServiceCursorWithoutAStop() {
        assertNull(resolve(activeBookId = book.book.id))
    }

    @Test fun normalSavedProgressRetainsItsVisualLocatorAndOffset() {
        assertSame(persisted, resolve())
        assertSame(persisted, resolve(activeBookId = "another-book"))
    }

    @Test fun stoppedProgressFromAnotherBookCannotOverrideThisBooksSavedProgress() {
        assertSame(persisted, resolve(stopped = persisted.copy(bookId = "another-book")))
        assertNull(resolve(stopped = persisted.copy(bookId = "another-book"), activeBookId = book.book.id))
    }

    @Test fun neitherForeignPersistedNorForeignStoppedProgressIsRestored() {
        val foreign = persisted.copy(bookId = "another-book")

        assertNull(resolveResumeProgress(book, foreign, foreign, null, ReadingPlaybackIntent.CONTINUE_AUDIO))
    }

    @Test fun thisBooksStopCanRestoreEvenWhenThePersistedRecordBelongsToAnotherBook() {
        val stopped = stoppedPlaybackProgress(book.book.id, snapshot, sentences = null)
        val resumed = resolveResumeProgress(book, persisted.copy(bookId = "another-book"), stopped,
            null, ReadingPlaybackIntent.CONTINUE_AUDIO)!!

        assertEquals(book.book.id, resumed.bookId)
        assertEquals(6_000L, resumed.audioOffsetMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySpeechBooksCannotResolveAnAudioCursor() {
        resolveResumeProgress(book.copy(sentences = emptyList()), persisted, null, null,
            ReadingPlaybackIntent.CONTINUE_AUDIO)
    }

    private fun resolve(
        stopped: ReadingProgress? = null,
        activeBookId: String? = null,
        intent: ReadingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO,
    ) = resolveResumeProgress(book, persisted, stopped, activeBookId, intent)
}
