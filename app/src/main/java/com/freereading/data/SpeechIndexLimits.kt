package com.freereading.data

/** 内存与磁盘索引共用的容量边界；同源句子共享的定位上下文只计一次。 */
internal object SpeechIndexLimits {
    const val MAX_SENTENCES: Int = 500_000
    const val MAX_TEXT_CHARACTERS: Long = 20_000_000L
    const val MAX_SOURCE_LOCATOR_CHARACTERS: Long = 64_000_000L
    const val MAX_BLOCK_CHARACTERS: Int = 65_536
    const val MAX_INDEX_BYTES: Long = 128L * 1024L * 1024L
}
