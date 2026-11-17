package com.freereading.data

/** Opening a visual page does not certify or materialize the complete speech index. */
data class ReadingBookBootstrap(
    val record: BookRecord,
    val progress: ReadingProgress,
    val cachedSpeechBook: SpeechBook? = null,
)
