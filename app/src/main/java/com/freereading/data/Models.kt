package com.freereading.data

import org.readium.r2.shared.publication.Locator

data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val addedAt: Long,
    val sentenceCount: Int,
    val coverPath: String? = null,
)

/** One original Readium text segment, shared by all sentences cut from that segment. */
class SpeechSource(
    val locator: Locator,
    val chapterIndex: Int,
    val chapterTitle: String,
) {
    val text: String = requireNotNull(locator.text.highlight) { "A speech source needs original text" }
}

/** Ranges use UTF-16 offsets with an exclusive end, just like Kotlin's substring. */
class SpeechSentence {
    val index: Int
    val source: SpeechSource?
    val startOffset: Int
    val endOffset: Int
    private val legacyText: String?
    private val legacyLocator: String?
    private val legacyChapterIndex: Int
    private val legacyChapterTitle: String?

    constructor(index: Int, source: SpeechSource, startOffset: Int, endOffset: Int) {
        require(startOffset >= 0 && endOffset > startOffset && endOffset <= source.text.length)
        require(isTextBoundary(source.text, startOffset) && isTextBoundary(source.text, endOffset))
        this.index = index
        this.source = source
        this.startOffset = startOffset
        this.endOffset = endOffset
        legacyText = null
        legacyLocator = null
        legacyChapterIndex = 0
        legacyChapterTitle = null
    }

    /** Keep v1 indexes and existing callers intact; opaque test locators are not parsed here. */
    constructor(index: Int, text: String, locatorJson: String, chapterIndex: Int, chapterTitle: String) {
        this.index = index
        source = null
        startOffset = 0
        endOffset = text.length
        legacyText = text
        legacyLocator = locatorJson
        legacyChapterIndex = chapterIndex
        legacyChapterTitle = chapterTitle
    }

    val text: String get() = source?.text?.substring(startOffset, endOffset) ?: legacyText!!
    val chapterIndex: Int get() = source?.chapterIndex ?: legacyChapterIndex
    val chapterTitle: String get() = source?.chapterTitle ?: legacyChapterTitle!!
    val locatorJson: String
        get() = source?.let {
            it.locator.copy(text = it.locator.text.substring(startOffset until endOffset)).toJSON().toString()
        } ?: legacyLocator!!

    private fun isTextBoundary(text: String, offset: Int): Boolean =
        offset == 0 || offset == text.length ||
            !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
}

data class SpeechBook(val book: BookRecord, val sentences: List<SpeechSentence>)

data class ReadingProgress(
    val bookId: String,
    val locatorJson: String? = null,
    val sentenceIndex: Int = 0,
    val audioOffsetMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
