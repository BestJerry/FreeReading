package com.freereading.audio

import com.freereading.data.SpeechSentence

/** 当前章节的句数与正文进度；fraction 是文字位置比例，并非整章音频时长比例。 */
internal data class ChapterProgress(val sentenceCount: Int, val fraction: Float)

/** 按文字码点数预建章节权重与边界，只需当前句的时长即可计算进度，无须预先合成整章。 */
internal class ChapterProgressIndex(sentences: List<SpeechSentence>) {
    private val prefixWeights = LongArray(sentences.size + 1)
    private val chapterStarts = IntArray(sentences.size)
    private val chapterEnds = IntArray(sentences.size)

    init {
        var start = 0
        for (index in sentences.indices) {
            if (index > 0 && sentences[index].chapterIndex != sentences[index - 1].chapterIndex) {
                // 每句的章节终点只填写一次，整本书的索引构建仍为 O(n)。
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

    /** 返回上一章首句；即使当前位于章中，也不返回本章开头，首章则返回 null。 */
    fun previousChapterStart(index: Int): Int? {
        require(index in chapterStarts.indices) { "Sentence index is outside the book" }
        val start = chapterStarts[index]
        return if (start > 0) chapterStarts[start - 1] else null
    }

    /** 返回下一章首句；末章没有后续目标时返回 null。 */
    fun nextChapterStart(index: Int): Int? {
        require(index in chapterStarts.indices) { "Sentence index is outside the book" }
        return chapterEnds[index].takeIf { it < chapterStarts.size }
    }

    /** 用全书句子索引和当前句音频位置，以 O(1) 计算限制在 0～1 内的章节正文进度。 */
    fun at(index: Int, positionMs: Long, durationMs: Long): ChapterProgress {
        require(index in chapterStarts.indices) { "Sentence index is outside the book" }
        val start = chapterStarts[index]
        val end = chapterEnds[index]
        val completed = prefixWeights[index] - prefixWeights[start]
        val weight = prefixWeights[index + 1] - prefixWeights[index]
        val total = prefixWeights[end] - prefixWeights[start]
        val progressed = when {
            durationMs <= 0L || positionMs <= 0L -> completed.toDouble()
            // 句末使用精确的整数前缀权重，确保前句结束与后句开始的章节进度一致。
            positionMs >= durationMs -> (completed + weight).toDouble()
            else -> completed.toDouble() + weight.toDouble() * (positionMs.toDouble() / durationMs.toDouble())
        }
        return ChapterProgress(end - start, (progressed / total.toDouble()).coerceIn(0.0, 1.0).toFloat())
    }
}
