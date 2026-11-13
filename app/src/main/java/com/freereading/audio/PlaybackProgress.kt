package com.freereading.audio

/** Keep delayed UI semantics safe even while a new playback snapshot replaces the old one. */
internal val PlaybackSnapshot.chapterProgressFraction: Float
    get() = if (chapterProgress.isFinite()) chapterProgress.coerceIn(0f, 1f) else 0f
