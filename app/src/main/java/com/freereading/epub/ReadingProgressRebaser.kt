package com.freereading.epub

import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import com.freereading.reader.SentenceLocatorMatcher
import com.freereading.reader.SpeechSentenceAnchors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.use

/** 在使用兼容 BOM 的解析器重建索引后，将旧阅读／听书游标映射到新句子位置。 */
internal object ReadingProgressRebaser {
    private val whitespace = Regex("\\s+")

    /**
     * 限定原资源，结合 CSS 选择器、旧 DOM 源码位置与文本上下文匹配新索引。
     * 只有确认仍为同一句时才保留句内音频时间；证据不足时保守回退并归零音频时间。
     */
    suspend fun rebase(publication: Publication, book: SpeechBook, saved: ReadingProgress,
                       legacySentence: SpeechSentence? = null): ReadingProgress =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (book.sentences.isEmpty()) return@withContext saved.copy(
                bookId = book.book.id, sentenceIndex = 0, audioOffsetMs = 0)
            val json = saved.locatorJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@withContext fallback(book, saved)
            val locator = runCatching { Locator.fromJSON(json) }.getOrNull()
                ?: return@withContext fallback(book, saved)
            val href = locator.href.removeFragment().removeQuery()
            val chapter = publication.readingOrder.indexOfFirst {
                it.url().removeFragment().removeQuery().isEquivalent(href)
            }
            if (chapter < 0) return@withContext fallback(book, saved)
            if (normalize(locator.text.highlight.orEmpty()).isEmpty()) {
                val evidence = legacySentence?.takeIf { it.index == saved.sentenceIndex && it.chapterIndex == chapter }
                    ?.let { runCatching { Locator.fromJSON(JSONObject(it.locatorJson)) }.getOrNull() }
                    ?.takeIf {
                        it.href.removeFragment().removeQuery().isEquivalent(href) &&
                            normalize(it.text.highlight.orEmpty()).isNotEmpty() &&
                            normalize(it.text.highlight.orEmpty()) == normalize(requireNotNull(legacySentence).text)
                    }
                if (evidence != null) {
                    // 阅读进度中的页面定位与听书句子独立；
                    // 先迁移旧句子，再保留精确的原页面供阅读导航使用。
                    return@withContext rebase(publication, book,
                        saved.copy(locatorJson = evidence.toJSON().toString(), audioOffsetMs = 0))
                        .copy(locatorJson = saved.locatorJson, audioOffsetMs = 0)
                }
            }
            val sentences = book.sentences.filter { it.chapterIndex == chapter }
            if (sentences.isEmpty()) return@withContext fallback(book, saved, preserveLocator = true)
            val anchors = SpeechSentenceAnchors.build(sentences.asSequence())
            val html = readHtml(publication, chapter)
            if (html.isNullOrEmpty()) return@withContext moved(saved, sentences.first(), false, preserveLocator = true)

            val selector = (locator.locations["cssSelector"] as? String)?.takeIf(String::isNotBlank)
            // 只保留旧 DOM 中这个小型标记，避免在 Android 堆限制下
            // 同时保留两份完整章节文档。
            val legacyTag = if (html.startsWith('\uFEFF') && selector != null) legacyTag(html, selector) else null
            currentCoroutineContext().ensureActive()
            val document = Parser.htmlParser().setTrackPosition(true).parseInput(html.withoutLeadingHtmlBom(), "")
            val direct = selector?.let { uniqueElement(document, it) }?.takeIf { it.isInBody(document) }
            val mapped = legacyTag?.let { tag ->
                document.getAllElements().singleOrNull {
                    val range = it.sourceRange()
                    range.isTracked && range.start().pos() == tag.position - 1 && it.normalName() == tag.name
                }
            }
            val oldHeadOnly = mapped != null && !mapped.isInBody(document)
            val mappedBody = mapped?.takeIf { it.isInBody(document) }
            val targetText = normalize(locator.text.highlight.orEmpty())
            if (targetText.isEmpty()) {
                // 阅读保存的是浏览器坐标中的页面定位，与听书句子独立；
                // 不能再对这种定位应用 BOM 导致的子节点编号偏移。
                val first = direct?.let { scope(anchors, it).minByOrNull { anchor -> anchor.index } }
                    ?.index?.let { book.sentences.getOrNull(it) } ?: sentences.first()
                return@withContext moved(saved, first, false, preserveLocator = true)
            }

            val textJson = json.optJSONObject("text")
            val hasSourceContext = textJson?.let { it.has("before") || it.has("after") } == true
            val rawAfter = textJson?.takeIf { it.has("after") && !it.isNull("after") }?.optString("after")
            val scopes = if (hasSourceContext || saved.audioOffsetMs > 0) listOf(mappedBody, direct) else listOf(direct, mappedBody)
            for (element in scopes.filterNotNull().distinct()) {
                currentCoroutineContext().ensureActive()
                match(scope(anchors, element), locator, element.cssSelector(), rawAfter, book)?.let { result ->
                    return@withContext moved(saved, result.sentence, result.sameSentence && !oldHeadOnly)
                }
            }
            // 被丢弃的 head／title 定位仍可能对应真实的正文标题；
            // 仅在同资源中回退匹配，且不继承这种合成标题的音频时间。
            match(anchors, locator, null, rawAfter, book)?.let { result ->
                return@withContext moved(saved, result.sentence, result.sameSentence && !oldHeadOnly)
            }
            moved(saved, sentences.first(), false)
        }

    /** 旧 DOM 元素在原始 HTML 中的位置与标签名，供移除开头 BOM 后重新对应元素。 */
    private data class SourceTag(val position: Int, val name: String)
    /** 新句子匹配结果，以及是否有足够证据确认音频仍处于原来的同一句。 */
    private data class Match(val sentence: SpeechSentence, val sameSentence: Boolean)

    /** 提取旧选择器指向的唯一元素的源码位置，不把整份旧 DOM 保留到后续处理。 */
    private fun legacyTag(html: String, selector: String): SourceTag? {
        val document = Parser.htmlParser().setTrackPosition(true).parseInput(html, "")
        val element = uniqueElement(document, selector) ?: return null
        val range = element.sourceRange()
        return if (range.isTracked) SourceTag(range.start().pos(), element.normalName()) else null
    }

    /** 尽量读取原章节作为定位证据；资源读取失败允许回退，但协程取消必须继续传播。 */
    private suspend fun readHtml(publication: Publication, chapter: Int): String? = try {
        publication.get(publication.readingOrder[chapter])?.use { resource ->
            resource.read().getOrNull()?.decodeString()?.getOrNull()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** 将匹配范围收窄到目标元素的子树；行内元素没有独立锚点时使用最近块级祖先。 */
    private fun scope(anchors: List<SentenceLocatorMatcher.Anchor>, element: Element): List<SentenceLocatorMatcher.Anchor> {
        if (element.normalName() == "body") return anchors
        val selectors = element.getAllElements().mapTo(HashSet()) { it.cssSelector() }
        val inside = anchors.filter { it.selector in selectors }
        if (inside.isNotEmpty() || element.isBlock) return inside
        val parent = element.parents().firstOrNull { it.isBlock } ?: return emptyList()
        val parentSelector = parent.cssSelector()
        return anchors.filter { it.selector == parentSelector }
    }

    /** 先筛文本和完整后文，再用上下文定位；重复文本未唯一确认时不保留句内时间。 */
    private fun match(
        candidates: List<SentenceLocatorMatcher.Anchor>,
        locator: Locator,
        selector: String?,
        rawAfter: String?,
        book: SpeechBook,
    ): Match? {
        val targetText = normalize(locator.text.highlight.orEmpty())
        val textMatches = candidates.filter { anchor ->
            normalize(anchor.text).let { it.isNotEmpty() && (it.contains(targetText) || targetText.contains(it)) }
        }
        if (textMatches.isEmpty()) return null
        // 完整句子定位保留精确的剩余原文，即使匹配器只使用其中 160 个字符；
        // 可利用完整后文的长度与内容，低成本区分重复句子。
        val exactRanges = if (rawAfter == null) emptyList() else textMatches.filter { anchor ->
            book.sentences.getOrNull(anchor.index)?.let { afterMatches(it, rawAfter) } == true
        }
        val eligible = exactRanges.takeIf { it.isNotEmpty() } ?: textMatches
        val target = SentenceLocatorMatcher.Anchor(-1, selector, locator.text.before.orEmpty(),
            locator.text.highlight.orEmpty(), locator.text.after.orEmpty())
        val index = SentenceLocatorMatcher.find(eligible, target) ?: return null
        val sentence = book.sentences.getOrNull(index) ?: return null
        val sameText = normalize(sentence.text) == targetText
        val verifiedRange = if (rawAfter != null) exactRanges.size == 1
            else textMatches.count { normalize(it.text) == targetText } == 1
        return Match(sentence, sameText && verifiedRange)
    }

    /** 直接比较共享片段剩余文本和后缀，避免为每个候选句创建完整 after 字符串。 */
    private fun afterMatches(sentence: SpeechSentence, after: String): Boolean {
        val source = sentence.source ?: return false
        val suffix = source.locator.text.after.orEmpty()
        val remaining = source.text.length - sentence.endOffset
        return after.length == remaining + suffix.length &&
            source.text.regionMatches(sentence.endOffset, after, 0, remaining) &&
            suffix.regionMatches(0, after, remaining, suffix.length)
    }

    /** 统一生成迁移进度，可保留原阅读页，但只有同句匹配才继承音频偏移。 */
    private fun moved(saved: ReadingProgress, sentence: SpeechSentence, sameSentence: Boolean,
                      preserveLocator: Boolean = false): ReadingProgress = saved.copy(
        sentenceIndex = sentence.index,
        locatorJson = if (preserveLocator) saved.locatorJson else sentence.locatorJson,
        audioOffsetMs = if (sameSentence) saved.audioOffsetMs.coerceAtLeast(0) else 0,
    )

    /** 无可靠定位时将旧句子序号限制到新索引合法范围，并清除不再可信的句内时间。 */
    private fun fallback(book: SpeechBook, saved: ReadingProgress, preserveLocator: Boolean = false): ReadingProgress =
        moved(saved, book.sentences[saved.sentenceIndex.coerceIn(book.sentences.indices)], false, preserveLocator)

    private fun uniqueElement(document: Document, selector: String): Element? =
        runCatching { document.select(selector).singleOrNull() }.getOrNull()

    private fun Element.isInBody(document: Document): Boolean = this === document.body() || parents().any { it === document.body() }
    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()
}
