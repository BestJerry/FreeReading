package com.freereading.data

/** 阅读首屏数据；通常按需加载完整听书索引，也可携带已有缓存或本次旧索引迁移的重建结果。 */
data class ReadingBookBootstrap(
    val record: BookRecord,
    val progress: ReadingProgress,
    val cachedSpeechBook: SpeechBook? = null,
)
