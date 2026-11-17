package com.freereading.audio

/** 为界面提供有限的 0～1 进度，避免快照切换或无效数值影响延迟执行的进度语义。 */
internal val PlaybackSnapshot.chapterProgressFraction: Float
    get() = if (chapterProgress.isFinite()) chapterProgress.coerceIn(0f, 1f) else 0f
