package com.freereading.audio

import com.freereading.data.SpeechSentence

internal data class ChapterProgress(val sentenceCount: Int, val fraction: Float)

/** Content progress requires only the current clip's duration, never audio for future sentences. */
internal class ChapterProgressIndex(sentences: List<SpeechSentence>) {
    private val prefixWeights = LongArray(sentences.size + 1)
    private val chapterStarts = IntArray(sentences.size)
    private val chapterEnds = IntArray(sentences.size)

    init {
        var start = 0
        for (index in sentences.indices) {
            if (index > 0 && sentences[index].chapterIndex != sentences[index - 1].chapterIndex) {
                // Each sentence's end is filled exactly once, so construction remains O(n).
                for (member in start until index) chapterEnds[member] = index
                start = index
            }
            chapterStarts[index] = start
            val text = sentences[index].text
            val weight = text.codePointCount(0, text.length).coerceAtLeast(1).toLong()
            prefixWeights[index + 1] = prefixWeights[index] + weight
        }
        for (member in start until sentences.size) chapterEnds[member] = sentences.size
    }

    /** Global sentence index and current clip media time map to an O(1), finite chapter fraction. */
    fun at(index: Int, positionMs: Long, durationMs: Long): ChapterProgress {
        require(index in chapterStarts.indices) { "Sentence index is outside the book" }
        val start = chapterStarts[index]
        val end = chapterEnds[index]
        val completed = prefixWeights[index] - prefixWeights[start]
        val weight = prefixWeights[index + 1] - prefixWeights[index]
        val total = prefixWeights[end] - prefixWeights[start]
        val progressed = when {
            durationMs <= 0L || positionMs <= 0L -> completed.toDouble()
            // Using the exact integer prefix at clip end keeps adjacent sentence boundaries equal.
            positionMs >= durationMs -> (completed + weight).toDouble()
            else -> completed.toDouble() + weight.toDouble() * (positionMs.toDouble() / durationMs.toDouble())
        }
        return ChapterProgress(end - start, (progressed / total.toDouble()).coerceIn(0.0, 1.0).toFloat())
    }
}
