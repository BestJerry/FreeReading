package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence

/** Capture a stop even before the reading document and its sentence locators have loaded. */
internal fun stoppedPlaybackProgress(
    bookId: String,
    snapshot: PlaybackSnapshot,
    sentences: List<SpeechSentence>?,
): ReadingProgress? {
    if (snapshot.bookId != bookId) return null
    return ReadingProgress(
        bookId = bookId,
        locatorJson = sentences?.getOrNull(snapshot.sentenceIndex)?.locatorJson,
        sentenceIndex = snapshot.sentenceIndex,
        audioOffsetMs = snapshot.positionMs.coerceAtLeast(0),
    )
}

/** A local stop is newer than the progress read before opening the EPUB or a clearing Hub. */
internal fun resolveResumeProgress(
    book: SpeechBook,
    persisted: ReadingProgress,
    stopped: ReadingProgress?,
    activeAudioBookId: String?,
    intent: ReadingPlaybackIntent,
): ReadingProgress? {
    require(book.sentences.isNotEmpty()) { "A reading book must contain speech sentences." }
    if (intent == ReadingPlaybackIntent.FROM_READING_LOCATION) return null
    val bookId = book.book.id
    stopped?.takeIf { it.bookId == bookId }?.let { progress ->
        val index = progress.sentenceIndex.coerceIn(book.sentences.indices)
        return progress.copy(
            locatorJson = book.sentences[index].locatorJson,
            sentenceIndex = index,
            audioOffsetMs = progress.audioOffsetMs.coerceAtLeast(0),
        )
    }
    if (activeAudioBookId == bookId) return null
    // Keep a persisted visual locator intact; it can restore a more precise reading page.
    return persisted.takeIf { it.bookId == bookId }
}
