package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot

/** Explicit reading navigation changes the next start; merely disabling follow does not. */
internal enum class ReadingPlaybackIntent {
    CONTINUE_AUDIO,
    FROM_READING_LOCATION,
}

internal enum class PrimaryPlaybackAction {
    PAUSE_OR_TOGGLE,
    RESUME_AUDIO,
    START_FROM_READING_LOCATION,
    RESUME_SAVED_OR_READING,
}

/** The button still pauses the current book while it is playing or preparing audio. */
internal fun primaryPlaybackAction(
    snapshot: PlaybackSnapshot,
    bookId: String,
    intent: ReadingPlaybackIntent,
): PrimaryPlaybackAction {
    val sameBook = snapshot.bookId == bookId
    return when {
        sameBook && (snapshot.isPlaying || snapshot.isPreparing) -> PrimaryPlaybackAction.PAUSE_OR_TOGGLE
        intent == ReadingPlaybackIntent.FROM_READING_LOCATION -> PrimaryPlaybackAction.START_FROM_READING_LOCATION
        sameBook -> PrimaryPlaybackAction.RESUME_AUDIO
        else -> PrimaryPlaybackAction.RESUME_SAVED_OR_READING
    }
}
