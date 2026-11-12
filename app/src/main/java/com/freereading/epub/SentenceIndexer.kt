@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.epub

import com.freereading.data.SpeechSentence
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentTokenizer
import org.readium.r2.shared.publication.services.content.content

/** The persisted order is independent of page layout, Android ICU version and playback speed. */
class SentenceIndexer {
    suspend fun index(publication: Publication): List<SpeechSentence> {
        val content = publication.content()
            ?: throw BookImportException("无法提取这本 EPUB 的正文")
        val chapterByHref = publication.readingOrder.mapIndexed { index, link ->
            link.url().removeFragment().removeQuery().toString() to index
        }.toMap()
        val titles = flatten(publication.tableOfContents).associate { link ->
            link.url().removeFragment().removeQuery().toString() to link.title.orEmpty()
        }
        val tokenizer = SentenceContentTokenizer()
        val sentences = ArrayList<SpeechSentence>()
        var locatorCharacters = 0L
        var textCharacters = 0L
        val iterator = content.iterator()
        while (iterator.hasNext()) {
            currentCoroutineContext().ensureActive()
            val element = iterator.next()
            for (tokenized in tokenizer.tokenize(element)) {
                if (tokenized !is Content.TextElement) continue
                for (segment in tokenized.segments) {
                    currentCoroutineContext().ensureActive()
                    val key = segment.locator.href.removeFragment().removeQuery().toString()
                    val chapter = chapterByHref[key]
                        ?: throw BookImportException("正文位置与 EPUB 章节不一致，无法建立听书索引")
                    val locatorJson = segment.locator.toJSON().toString()
                    locatorCharacters += locatorJson.length
                    textCharacters += segment.text.length
                    if (sentences.size >= 100_000 || textCharacters > 8_000_000L ||
                        locatorCharacters > 32_000_000L
                    ) {
                        throw BookImportException("书籍正文或位置索引过大，暂不支持导入")
                    }
                    sentences.add(
                        SpeechSentence(
                            index = sentences.size,
                            text = segment.text,
                            locatorJson = locatorJson,
                            chapterIndex = chapter,
                            chapterTitle = titles[key].orEmpty().ifBlank {
                                publication.readingOrder[chapter].title.orEmpty()
                            }.take(2048),
                        )
                    )
                }
            }
        }
        if (sentences.isEmpty()) {
            throw BookImportException("这本 EPUB 没有可朗读的文字，暂不支持扫描图片书籍")
        }
        return sentences
    }

    private fun flatten(links: List<Link>): List<Link> = buildList {
        for (link in links) {
            add(link)
            addAll(flatten(link.children))
        }
    }

    /**
     * Work from Readium's original locator text, not normalized display text. substring() retains
     * the source selector and exact before/after context, including repeated sentences in a block.
     */
    internal class SentenceContentTokenizer : ContentTokenizer {
        override fun tokenize(data: Content.Element): List<Content.Element> {
            if (data !is Content.TextElement) return emptyList()
            if (data.segments.sumOf { (it.locator.text.highlight ?: it.text).length.toLong() } > 65_536L) {
                throw BookImportException("书籍包含过长的正文段落，暂不支持建立听书索引")
            }
            val segments = data.segments.flatMap { segment ->
                val source = segment.locator.text.highlight ?: segment.text
                val locator = if (segment.locator.text.highlight == null) {
                    segment.locator.copy(text = Locator.Text(highlight = source))
                } else {
                    segment.locator
                }
                SentenceRanges.split(source).map { range ->
                    segment.copy(
                        text = source.substring(range),
                        locator = locator.copy(text = locator.text.substring(range)),
                    )
                }
            }
            return listOf(data.copy(segments = segments))
        }
    }
}

/** Pure deterministic UTF-16 ranges. Keep this algorithm versioned with the persisted index. */
internal object SentenceRanges {
    const val MAX_LENGTH = 500
    private const val ENDINGS = "。！？!?；;\n\r"
    private const val CLOSING = "\"'”’）)]】》」』〕〉〗〙〛"
    private const val SOFT = "，,、：:"

    fun split(text: String, maxLength: Int = MAX_LENGTH): List<IntRange> {
        require(maxLength >= 2)
        val ranges = ArrayList<IntRange>()
        var start = 0
        var offset = 0
        var softBreak = 0

        fun append(end: Int) {
            var first = start
            var last = end
            while (first < last && text[first].isWhitespace()) first += 1
            while (last > first && text[last - 1].isWhitespace()) last -= 1
            if (first < last && text.substring(first, last).any { it.isLetterOrDigit() }) {
                ranges.add(first until last)
            }
            start = end
            offset = end
            softBreak = end
        }

        while (offset < text.length) {
            val char = text[offset]
            val next = offset + Character.charCount(text.codePointAt(offset))
            if (next - start > maxLength) {
                val cut = if (softBreak > start + (maxLength / 2)) softBreak else offset
                append(cut)
                continue
            }
            val dotEndsSentence = char == '.' &&
                (next == text.length || text[next].isWhitespace() || text[next] in CLOSING)
            val ellipsisEndsSentence = char == '…' && next < text.length && text[next] == '…'
            if (char in ENDINGS || dotEndsSentence || ellipsisEndsSentence) {
                var end = next
                while (end < text.length && end - start < maxLength &&
                    (text[end] in ENDINGS || text[end] in CLOSING || text[end] == '…')
                ) {
                    end += 1
                }
                append(end)
                continue
            }
            if (char.isWhitespace() || char in SOFT) softBreak = next
            offset = next
        }
        if (start < text.length) append(text.length)
        return ranges
    }
}
