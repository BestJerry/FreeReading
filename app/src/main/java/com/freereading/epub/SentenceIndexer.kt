@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.epub

import com.freereading.data.SpeechIndexLimits
import com.freereading.data.SpeechSentence
import com.freereading.data.SpeechSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/**
 * 从 EPUB 的阅读顺序生成确定性的听书句子索引，不依赖分页、Android ICU 版本或播放速度。
 * 每个原文片段只保存一次定位，切出的句子记录范围，并在建立索引时执行容量边界检查。
 */
class SentenceIndexer {
    /** 按正文资源遍历文本并报告资源进度；无可朗读文字、定位不一致或容量超限时拒绝索引。 */
    suspend fun index(publication: Publication, onProgress: ((Long, Long) -> Unit)? = null): List<SpeechSentence> {
        val total = publication.readingOrder.size.toLong()
        onProgress?.invoke(0, total)
        val content = publication.content()
            ?: throw BookImportException("无法提取这本 EPUB 的正文")
        val chapterByHref = publication.readingOrder.mapIndexed { index, link ->
            link.url().removeFragment().removeQuery().toString() to index
        }.toMap()
        val titles = flatten(publication.tableOfContents).associate { link ->
            link.url().removeFragment().removeQuery().toString() to link.title.orEmpty()
        }
        val sentences = ArrayList<SpeechSentence>()
        var sourceLocatorCharacters = 0L
        var textCharacters = 0L
        val iterator = content.iterator()
        var completedResources = 0L
        while (iterator.hasNext()) {
            currentCoroutineContext().ensureActive()
            val nextElement = iterator.next()
            if (onProgress != null) {
                val resourceKey = nextElement.locator.href.removeFragment().removeQuery().toString()
                val resourceIndex = chapterByHref[resourceKey]?.toLong()
                // 进入下一个资源才确认之前的资源已遍历完成；
                // 其中也包括 Readium 跳过的空章和纯图片章节。
                if (resourceIndex != null && resourceIndex > completedResources) {
                    completedResources = resourceIndex
                    onProgress(completedResources, total)
                }
            }
            val element = nextElement as? Content.TextElement ?: continue
            if (element.segments.sumOf { (it.locator.text.highlight ?: it.text).length.toLong() } >
                SpeechIndexLimits.MAX_BLOCK_CHARACTERS
            ) {
                throw BookImportException("书籍包含过长的正文段落，暂不支持建立听书索引")
            }
            for (segment in element.segments) {
                currentCoroutineContext().ensureActive()
                // 使用 Readium 原始 highlight，保留原文空白与重复文本，确保句子偏移对应真实内容。
                val text = segment.locator.text.highlight ?: segment.text
                val ranges = SentenceRanges.split(text)
                if (ranges.isEmpty()) continue
                val key = segment.locator.href.removeFragment().removeQuery().toString()
                val chapter = chapterByHref[key]
                    ?: throw BookImportException("正文位置与 EPUB 章节不一致，无法建立听书索引")
                val locator = if (segment.locator.text.highlight == null) {
                    segment.locator.copy(text = Locator.Text(highlight = text))
                } else segment.locator
                // 原始定位只保存一次；逐句展开 before／after 会令长段落的上下文量平方增长，
                // 还会为每个句子重复全部 JSON 字段。
                sourceLocatorCharacters += locator.toJSON().toString().length
                if (sourceLocatorCharacters > SpeechIndexLimits.MAX_SOURCE_LOCATOR_CHARACTERS) {
                    throw BookImportException("书籍定位信息过大，暂不支持导入")
                }
                val title = titles[key].orEmpty().ifBlank {
                    publication.readingOrder[chapter].title.orEmpty()
                }.take(2048)
                val source = SpeechSource(locator, chapter, title)
                for (range in ranges) {
                    currentCoroutineContext().ensureActive()
                    if (sentences.size >= SpeechIndexLimits.MAX_SENTENCES) {
                        throw BookImportException("书籍句子数量超过50万句，暂不支持导入")
                    }
                    textCharacters += range.last - range.first + 1
                    if (textCharacters > SpeechIndexLimits.MAX_TEXT_CHARACTERS) {
                        throw BookImportException("书籍正文超过2000万字符，暂不支持导入")
                    }
                    sentences.add(
                        SpeechSentence(sentences.size, source, range.first, range.last + 1)
                    )
                }
            }
        }
        if (sentences.isEmpty()) {
            throw BookImportException("这本 EPUB 没有可朗读的文字，暂不支持扫描图片书籍")
        }
        onProgress?.invoke(total, total)
        return sentences
    }

    /** 展平嵌套目录以查找资源标题；同资源多个锚点仍使用正文资源作为章节边界。 */
    private fun flatten(links: List<Link>): List<Link> = buildList {
        for (link in links) {
            add(link)
            addAll(flatten(link.children))
        }
    }
}

/** 纯函数分句规则，返回确定性的 UTF-16 范围；规则变更必须与持久化定位索引版本同步。 */
internal object SentenceRanges {
    const val MAX_LENGTH = 500
    private const val ENDINGS = "。！？!?；;\n\r"
    private const val CLOSING = "\"'”’）)]】》」』〕〉〗〙〛"
    private const val SOFT = "，,、：:"

    /**
     * 优先按句末标点分句，长句尽量在后半段的空白或软标点处分割。
     * 裁去两端空白并过滤无字母／数字片段；按 Unicode 码点前进，避免切断 UTF-16 代理对。
     */
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
