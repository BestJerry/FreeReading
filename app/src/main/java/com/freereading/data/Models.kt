package com.freereading.data

import org.readium.r2.shared.publication.Locator

/** 书架元数据；id 为原始 EPUB 的 SHA-256，addedAt 用于区分同书移除后重新导入的记录。 */
data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val addedAt: Long,
    val sentenceCount: Int,
    val coverPath: String? = null,
)

/** 原始 Readium 文本片段；由同一片段切出的句子共享定位和上下文，避免重复保存长段落。 */
class SpeechSource(
    val locator: Locator,
    val chapterIndex: Int,
    val chapterTitle: String,
) {
    val text: String = requireNotNull(locator.text.highlight) { "A speech source needs original text" }
}

/**
 * 一个可朗读句子，同时兼容旧版独立文本和新版共享片段两种表示。
 * 范围采用 UTF-16 偏移，结束位置不包含在句子内，与 Kotlin 的 substring 一致。
 */
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

    /** 兼容 v1 索引及已有调用方；此处保留原始定位字符串，不提前解析。 */
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
    /** 按需展开句子的前后文和定位 JSON，避免所有句子同时复制完整段落。 */
    val locatorJson: String
        get() = source?.let {
            it.locator.copy(text = it.locator.text.substring(startOffset until endOffset)).toJSON().toString()
        } ?: legacyLocator!!

    private fun isTextBoundary(text: String, offset: Int): Boolean =
        offset == 0 || offset == text.length ||
            !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
}

/** 书籍元数据与按 EPUB 正文顺序排列的完整听书句子列表。 */
data class SpeechBook(val book: BookRecord, val sentences: List<SpeechSentence>)

/**
 * 阅读／听书共享的持久化进度：页面定位与音频句子索引可独立更新。
 * audioOffsetMs 为句内媒体时间，updatedAt 用于拒绝晚到的旧进度写入。
 */
data class ReadingProgress(
    val bookId: String,
    val locatorJson: String? = null,
    val sentenceIndex: Int = 0,
    val audioOffsetMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
