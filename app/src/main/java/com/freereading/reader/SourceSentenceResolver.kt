package com.freereading.reader

import com.freereading.epub.withoutLeadingHtmlBom
import java.util.IdentityHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import org.jsoup.select.Selector

/** 原文定位的明确依据：CSS 元素、目录锚点或显式的资源起点。 */
internal sealed interface SourceSpeechAnchor {
    /** Readium 提供的可见元素选择器。 */
    data class Element(val selector: String) : SourceSpeechAnchor
    /** EPUB 内部链接或目录指向的元素 ID。 */
    data class Fragment(val id: String) : SourceSpeechAnchor
    /** 用户明确选择无锚点目录项时，从该资源的第一句开始。 */
    data object ResourceStart : SourceSpeechAnchor
}

/** 解析调用方提供的单个原始 XHTML，结合 DOM 顺序定位目录边界或可朗读句子。 */
internal class SourceSentenceResolver(html: String) {
    private val document = Jsoup.parse(html.withoutLeadingHtmlBom())
    private val elements = document.getAllElements().toList()
    private val documentOrder = IdentityHashMap<Element, Int>().apply {
        elements.forEachIndexed { index, element -> put(element, index) }
    }
    private val selectors = HashMap<String, Element?>()

    /** 仅在当前资源内找到可见元素之前最近的目录边界；重复 ID 不作为可靠证据。 */
    @Synchronized
    fun resolveContents(entries: List<Pair<Int, String?>>, anchor: SourceSpeechAnchor): Int? {
        val resourceStart = entries.lastOrNull { it.second == null }?.first
        if (anchor == SourceSpeechAnchor.ResourceStart) return resourceStart
        val target = when (anchor) {
            is SourceSpeechAnchor.Element -> uniqueElement(anchor.selector)
            is SourceSpeechAnchor.Fragment -> elements.filter { it.id() == anchor.id }.singleOrNull()
            SourceSpeechAnchor.ResourceStart -> null
        } ?: return null
        // 整份文档级选择器无法证明当前可见章节，不能据此选中目录项。
        if (target.normalName() in setOf("html", "body")) return null
        val targetOrder = documentOrder[target] ?: return null
        val visibleStartOrder = if (anchor is SourceSpeechAnchor.Element) {
            contentsTextStartOrder(target, targetOrder)
        } else targetOrder
        val wanted = entries.mapNotNull { it.second }.toHashSet()
        val boundaries = HashMap<String, Element?>()
        for (element in elements) {
            val id = element.id()
            if (id in wanted) boundaries[id] = if (boundaries.containsKey(id)) null else element
        }
        return entries.mapNotNull { (index, fragment) ->
            val element = fragment?.let(boundaries::get) ?: return@mapNotNull null
            val order = documentOrder[element] ?: return@mapNotNull null
            (index to order).takeIf { order <= visibleStartOrder }
        }.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })?.first ?: resourceStart
    }

    /** 标题首段文字之前的空行内锚点属于该标题，定位目录时一并越过这些前置标记。 */
    private fun contentsTextStartOrder(target: Element, targetOrder: Int): Int {
        var leadingOrder = targetOrder
        var hasText = false
        NodeTraversor.filter(NodeFilter { node, _ ->
            if (node is TextNode && node.wholeText.isNotBlank()) {
                hasText = true
                NodeFilter.FilterResult.STOP
            } else {
                if (node is Element) documentOrder[node]?.let { leadingOrder = it }
                NodeFilter.FilterResult.CONTINUE
            }
        }, target)
        // 无文本容器不能证明其后代已经可见，不能据此提前进入后面的章节。
        return if (hasText) leadingOrder else targetOrder
    }

    /** 优先返回目标所属索引文本块或子树内的首句；没有可读句时向后查找，歧义或失效锚点不回退章首。 */
    @Synchronized
    fun resolve(candidates: List<SentenceLocatorMatcher.Anchor>, anchor: SourceSpeechAnchor): Int? {
        if (anchor == SourceSpeechAnchor.ResourceStart) return candidates.minOfOrNull { it.index }
        val target = when (anchor) {
            is SourceSpeechAnchor.Element -> uniqueElement(anchor.selector)
            is SourceSpeechAnchor.Fragment -> anchor.id.takeIf { it.isNotBlank() }
                ?.let { id -> elements.filter { it.id() == id }.singleOrNull() }
            SourceSpeechAnchor.ResourceStart -> null
        } ?: return null
        val resolved = candidates.mapNotNull { candidate ->
            if (candidate.text.isBlank()) return@mapNotNull null
            val selector = candidate.selector ?: return@mapNotNull null
            uniqueElement(selector)?.let { IndexedElement(candidate.index, it) }
        }
        if (resolved.isEmpty()) return null

        // 行内标记归属最近的已索引文本块；没有自身正文索引的结构容器
        // 不能把片段起点回退到前面的子段落。
        val parentBlock = target.parents().firstOrNull { it.isBlock }
        val scope = if (!target.isBlock && (anchor is SourceSpeechAnchor.Fragment || target.hasText()) &&
            parentBlock != null &&
            parentBlock.normalName() !in setOf("html", "body") &&
            resolved.any { it.element === parentBlock }
        ) {
            parentBlock
        } else {
            target
        }

        val inside = resolved.filter { it.element.isWithin(scope) }
        if (inside.isNotEmpty()) return inside.minOf { it.index }

        // 分隔符前后的文字可能被 Readium 赋予同一祖先选择器；
        // 缺少文本节点范围时，无法确定应从祖先开头读还是越过它，因此返回未匹配。
        if (target.isBlock && resolved.any {
                it.element.isBlock && it.element !== target && target.isWithin(it.element)
            }
        ) return null

        // 空块、分隔符和图片没有朗读句子，只能查找真实目标之后的元素；
        // 目标不存在时已在前面返回 null，不允许用后续正文掩盖失效锚点。
        val targetOrder = documentOrder[scope] ?: return null
        return resolved.asSequence()
            .filter { (documentOrder[it.element] ?: -1) > targetOrder }
            // 句子编号已遵循 Readium 原文遍历顺序，包含嵌套块和共用选择器的多个句子。
            .minOfOrNull { it.index }
    }

    /** 只接受唯一命中的元素并缓存查询结果；非法或歧义选择器都视为未匹配。 */
    private fun uniqueElement(selector: String): Element? {
        if (selector.isBlank()) return null
        if (selectors.containsKey(selector)) return selectors[selector]
        val element = try {
            val attribute = QUOTED_ATTRIBUTE.matchEntire(selector)
            if (attribute != null) {
                // Jsoup 1.18.1 不会对带引号的属性值做 CSS 反转义，
                // 只补充处理完整的属性选择器，不改写标识符转义或组合选择器。
                val value = decodeCssString(attribute.groupValues[3], attribute.groupValues[2][0])
                val key = attribute.groupValues[1]
                if (value == null) null else elements.filter {
                    it.hasAttr(key) && it.attr(key) == value
                }.singleOrNull()
            } else {
                document.select(selector).singleOrNull()
            }
        } catch (_: Selector.SelectorParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        selectors[selector] = element
        return element
    }

    /** 解码 CSS 字符串转义；不完整转义或禁用字符返回 null，非法转义码点替换为 U+FFFD。 */
    private fun decodeCssString(raw: String, quote: Char): String? {
        val decoded = StringBuilder()
        var offset = 0
        while (offset < raw.length) {
            val char = raw[offset++]
            if (char == quote || char in "\n\r\u000c\u0000") return null
            if (char != '\\') {
                decoded.append(char)
                continue
            }
            if (offset == raw.length) return null
            val escaped = raw[offset++]
            when {
                escaped in "0123456789abcdefABCDEF" -> {
                    val start = offset - 1
                    while (offset < raw.length && offset - start < 6 && raw[offset] in "0123456789abcdefABCDEF") offset += 1
                    val codePoint = raw.substring(start, offset).toInt(16)
                    decoded.appendCodePoint(if (codePoint == 0 || codePoint > 0x10ffff || codePoint in 0xd800..0xdfff) 0xfffd else codePoint)
                    if (offset < raw.length && raw[offset] in " \t\n\r\u000c") {
                        val whitespace = raw[offset++]
                        if (whitespace == '\r' && offset < raw.length && raw[offset] == '\n') offset += 1
                    }
                }
                escaped == '\r' -> if (offset < raw.length && raw[offset] == '\n') offset += 1
                escaped == '\n' || escaped == '\u000c' -> Unit
                escaped == '\u0000' -> return null
                else -> decoded.append(escaped)
            }
        }
        return decoded.toString()
    }

    private fun Element.isWithin(ancestor: Element): Boolean {
        var current: Element? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent()
        }
        return false
    }

    /** 将全书句子编号与本资源中唯一匹配的 DOM 元素关联。 */
    private data class IndexedElement(val index: Int, val element: Element)

    private companion object {
        val QUOTED_ATTRIBUTE = Regex("""^\[\s*([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*(['"])([\s\S]*)\2\s*]$""")
    }
}
