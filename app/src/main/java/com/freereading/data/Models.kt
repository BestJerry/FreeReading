package com.freereading.data

data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val addedAt: Long,
    val sentenceCount: Int,
    val coverPath: String? = null,
)

data class SpeechSentence(
    val index: Int,
    val text: String,
    val locatorJson: String,
    val chapterIndex: Int,
    val chapterTitle: String,
)

data class SpeechBook(val book: BookRecord, val sentences: List<SpeechSentence>)

data class ReadingProgress(
    val bookId: String,
    val locatorJson: String? = null,
    val sentenceIndex: Int = 0,
    val audioOffsetMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
