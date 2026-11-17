package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot
import com.freereading.data.BookRecord
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence

/** 捕获本书停止时的句子与音频偏移；正文索引尚未加载时也能保留恢复游标。 */
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

/** 主动跳转正文时不提供恢复游标；否则优先本页停止位置，再在本书无音频会话时使用持久化进度。 */
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
    // 保留已保存的视觉定位，它比单独的句子编号更精确地还原阅读页。
    return persisted.takeIf { it.bookId == bookId }
}

/** 在完整听书索引解码前恢复阅读页；有缓存时复用相同的恢复规则。 */
internal fun resolveReadingResumeProgress(
    record: BookRecord,
    persisted: ReadingProgress,
    stopped: ReadingProgress?,
    activeAudioBookId: String?,
    intent: ReadingPlaybackIntent,
    cachedBook: SpeechBook?,
): ReadingProgress? {
    if (cachedBook != null) return resolveResumeProgress(cachedBook, persisted, stopped, activeAudioBookId, intent)
    require(record.sentenceCount > 0) { "A reading book must contain speech sentences." }
    if (intent == ReadingPlaybackIntent.FROM_READING_LOCATION) return null
    stopped?.takeIf { it.bookId == record.id }?.let { progress ->
        return progress.copy(sentenceIndex = progress.sentenceIndex.coerceIn(0, record.sentenceCount - 1),
            audioOffsetMs = progress.audioOffsetMs.coerceAtLeast(0))
    }
    if (activeAudioBookId == record.id) return null
    return persisted.takeIf { it.bookId == record.id }
}
