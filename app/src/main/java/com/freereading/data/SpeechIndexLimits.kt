package com.freereading.data

/** Shared in-memory and on-disk bounds; shared source contexts are counted only once. */
internal object SpeechIndexLimits {
    const val MAX_SENTENCES: Int = 500_000
    const val MAX_TEXT_CHARACTERS: Long = 20_000_000L
    const val MAX_SOURCE_LOCATOR_CHARACTERS: Long = 64_000_000L
    const val MAX_BLOCK_CHARACTERS: Int = 65_536
    const val MAX_INDEX_BYTES: Long = 128L * 1024L * 1024L
}
