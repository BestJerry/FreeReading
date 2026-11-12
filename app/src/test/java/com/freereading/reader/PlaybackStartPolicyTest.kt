package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStartPolicyTest {
    private val currentBook = "currently-reading"
    private val pausedAudio = PlaybackSnapshot(
        bookId = currentBook,
        sentenceIndex = 7,
        positionMs = 2_400,
        isPlaying = false,
        isPreparing = false,
    )

    @Test fun pausedSameBookAfterContentsNavigationStartsAtTheReadingLocation() {
        assertEquals(
            PrimaryPlaybackAction.START_FROM_READING_LOCATION,
            primaryPlaybackAction(pausedAudio, currentBook, ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
    }

    @Test fun pausedSameBookWithoutNavigationResumesItsExistingSentenceAndOffset() {
        assertEquals(
            PrimaryPlaybackAction.RESUME_AUDIO,
            primaryPlaybackAction(pausedAudio, currentBook, ReadingPlaybackIntent.CONTINUE_AUDIO),
        )
    }

    @Test fun playingSameBookStillPausesAfterUserBrowsesElsewhere() {
        assertEquals(
            PrimaryPlaybackAction.PAUSE_OR_TOGGLE,
            primaryPlaybackAction(pausedAudio.copy(isPlaying = true), currentBook,
                ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
    }

    @Test fun preparingSameBookKeepsTheCurrentButtonActionRatherThanStartingAnotherRequest() {
        assertEquals(
            PrimaryPlaybackAction.PAUSE_OR_TOGGLE,
            primaryPlaybackAction(pausedAudio.copy(isPreparing = true), currentBook,
                ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
    }

    @Test fun playingAnotherBookDoesNotTurnThisBooksPrimaryButtonIntoPause() {
        val anotherBookPlaying = pausedAudio.copy(bookId = "another-book", isPlaying = true)
        assertEquals(
            PrimaryPlaybackAction.START_FROM_READING_LOCATION,
            primaryPlaybackAction(anotherBookPlaying, currentBook, ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
        assertEquals(
            PrimaryPlaybackAction.RESUME_SAVED_OR_READING,
            primaryPlaybackAction(anotherBookPlaying, currentBook, ReadingPlaybackIntent.CONTINUE_AUDIO),
        )
    }

    @Test fun pausedAnotherBookDoesNotReuseItsAudioCursor() {
        assertEquals(
            PrimaryPlaybackAction.RESUME_SAVED_OR_READING,
            primaryPlaybackAction(pausedAudio.copy(bookId = "another-book"), currentBook,
                ReadingPlaybackIntent.CONTINUE_AUDIO),
        )
    }

    @Test fun noActiveAudioAfterManualReadingStartsAtTheVisibleLocation() {
        assertEquals(
            PrimaryPlaybackAction.START_FROM_READING_LOCATION,
            primaryPlaybackAction(PlaybackSnapshot(), currentBook, ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
    }

    @Test fun noActiveAudioWithoutNavigationUsesTheSavedProgressWhenAvailable() {
        assertEquals(
            PrimaryPlaybackAction.RESUME_SAVED_OR_READING,
            primaryPlaybackAction(PlaybackSnapshot(), currentBook, ReadingPlaybackIntent.CONTINUE_AUDIO),
        )
    }

    @Test fun aPlaybackErrorDoesNotDiscardAnExplicitReadingStart() {
        assertEquals(
            PrimaryPlaybackAction.START_FROM_READING_LOCATION,
            primaryPlaybackAction(pausedAudio.copy(error = "语音合成失败"), currentBook,
                ReadingPlaybackIntent.FROM_READING_LOCATION),
        )
    }
}
