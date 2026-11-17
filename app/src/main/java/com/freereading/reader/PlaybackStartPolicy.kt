package com.freereading.reader

import com.freereading.audio.PlaybackSnapshot

/** 区分继续听书、从新阅读位置起播和失败重试；单独关闭原文跟随不改变起播意图。 */
internal enum class ReadingPlaybackIntent {
    CONTINUE_AUDIO,
    FROM_READING_LOCATION,
    RETRY_AUDIO_START,
}

/** 将播放按钮的界面操作归一为服务控制或阅读定位起播，避免混用两种游标。 */
internal enum class PrimaryPlaybackAction {
    PAUSE_OR_TOGGLE,
    RESUME_AUDIO,
    START_FROM_READING_LOCATION,
    RESUME_SAVED_OR_READING,
}

/** 根据当前书籍与起播意图决定主按钮操作；本书播放或准备中时，优先允许暂停。 */
internal fun primaryPlaybackAction(
    snapshot: PlaybackSnapshot,
    bookId: String,
    intent: ReadingPlaybackIntent,
): PrimaryPlaybackAction {
    val sameBook = snapshot.bookId == bookId
    return when {
        sameBook && (snapshot.isPlaying || snapshot.isPreparing) -> PrimaryPlaybackAction.PAUSE_OR_TOGGLE
        intent == ReadingPlaybackIntent.RETRY_AUDIO_START -> PrimaryPlaybackAction.RESUME_SAVED_OR_READING
        intent == ReadingPlaybackIntent.FROM_READING_LOCATION -> PrimaryPlaybackAction.START_FROM_READING_LOCATION
        sameBook -> PrimaryPlaybackAction.RESUME_AUDIO
        else -> PrimaryPlaybackAction.RESUME_SAVED_OR_READING
    }
}
