package com.freereading.reader

import java.util.IdentityHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Selector

internal sealed interface SourceSpeechAnchor {
    data class Element(val selector: String) : SourceSpeechAnchor
    data class Fragment(val id: String) : SourceSpeechAnchor
    data object ResourceStart : SourceSpeechAnchor
}

/** Resolves only within the original XHTML resource supplied by the caller. */
internal class SourceSentenceResolver(html: String) {
    private val document = Jsoup.parse(html)
    private val elements = document.getAllElements().toList()
    private val documentOrder = IdentityHashMap<Element, Int>().apply {
        elements.forEachIndexed { index, element -> put(element, index) }
    }
    private val selectors = HashMap<String, Element?>()

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

        // An inline marker belongs to its nearest indexed text block. A structural wrapper
        // without its own indexed text must not rewind a fragment to preceding child paragraphs.
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

        // Readium can give text before and after a divider the same ancestor selector. Without
        // a text-node range, neither rewinding that ancestor nor skipping it is justified.
        if (target.isBlock && resolved.any {
                it.element.isBlock && it.element !== target && target.isWithin(it.element)
            }
        ) return null

        // Empty blocks, dividers and images have no speech sentence. Only DOM elements after
        // this real target are eligible; a missing target has already returned null above.
        val targetOrder = documentOrder[scope] ?: return null
        return resolved.asSequence()
            .filter { (documentOrder[it.element] ?: -1) > targetOrder }
            // Sentence indices already follow Readium's source traversal, including text in
            // nested blocks and several sentences sharing the same selector.
            .minOfOrNull { it.index }
    }

    private fun uniqueElement(selector: String): Element? {
        if (selector.isBlank()) return null
        if (selectors.containsKey(selector)) return selectors[selector]
        val element = try {
            val attribute = QUOTED_ATTRIBUTE.matchEntire(selector)
            if (attribute != null) {
                // Jsoup 1.18.1 does not CSS-unescape quoted attribute values. Interpret only
                // this complete selector form; never rewrite escaped identifiers or combinators.
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

    private data class IndexedElement(val index: Int, val element: Element)

    private companion object {
        val QUOTED_ATTRIBUTE = Regex("""^\[\s*([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*(['"])([\s\S]*)\2\s*]$""")
    }
}
