package com.freereading.reader

import com.freereading.data.SpeechSentence
import com.freereading.data.SpeechSource
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Compact matching evidence only; navigation continues to use each sentence's full locator. */
internal object SpeechSentenceAnchors {
    private const val CONTEXT_LENGTH = 160
    private val whitespace = Regex("\\s+")

    fun build(sentences: Sequence<SpeechSentence>): List<SentenceLocatorMatcher.Anchor> = buildList {
        // Even 160-character snapshots per sentence can exceed the heap for a large chapter.
        // Keep ranges here and build only the source context currently requested by the matcher.
        val provider = ContextProvider()
        for (sentence in sentences) {
            val source = sentence.source
            if (source == null) {
                val locator = runCatching { Locator.fromJSON(JSONObject(sentence.locatorJson)) }.getOrNull()
                    ?: continue
                val before = normalize(locator.text.before.orEmpty())
                val after = normalize(locator.text.after.orEmpty())
                add(SentenceLocatorMatcher.Anchor(
                    index = sentence.index,
                    selector = locator.locations["cssSelector"] as? String,
                    before = beforeWindow(before, 0, before.length),
                    text = locator.text.highlight.orEmpty(),
                    after = afterWindow(after, 0, after.length),
                ))
                continue
            }
            add(SentenceLocatorMatcher.Anchor(
                sentence.index,
                source.locator.locations["cssSelector"] as? String,
                RangeText(sentence, provider),
            ))
        }
    }

    private class RangeText(
        private val sentence: SpeechSentence,
        private val provider: ContextProvider,
    ) : SentenceLocatorMatcher.Anchor.Text {
        override val before: String get() = provider.context(requireNotNull(sentence.source)).before(sentence)
        override val text: String get() = sentence.text
        override val after: String get() = provider.context(requireNotNull(sentence.source)).after(sentence)
    }

    private class ContextProvider {
        private var current: Pair<SpeechSource, SourceContext>? = null

        fun context(source: SpeechSource): SourceContext {
            val cached = current
            if (cached?.first === source) return cached.second
            return SourceContext(source).also { current = source to it }
        }
    }

    private class SourceContext(private val source: SpeechSource) {
        private val beforeLength = source.locator.text.before.orEmpty().length
        private val length = beforeLength + source.text.length + source.locator.text.after.orEmpty().length
        private val beforeEnds = IntArray(length + 1)
        private val afterStarts = IntArray(length + 1)
        private val normalized: String
        private val firstContent: Int
        private val contentEnd: Int

        init {
            // Normalize once per source, not once per sentence. Using the actual Regex also
            // preserves Android/JVM differences in the Unicode characters matched by \\s.
            val raw = source.locator.text.before.orEmpty() + source.text + source.locator.text.after.orEmpty()
            val regexWhitespace = BooleanArray(length)
            for (match in whitespace.findAll(raw)) {
                for (index in match.range) regexWhitespace[index] = true
            }
            val result = StringBuilder(length)
            var previousWasRegexWhitespace = false
            var lastContentEnd = 0
            for (index in 0 until length) {
                val char = raw[index]
                val isRegexWhitespace = regexWhitespace[index]
                if (!isRegexWhitespace) result.append(char)
                else if (!previousWasRegexWhitespace) result.append(' ')
                previousWasRegexWhitespace = isRegexWhitespace
                if (!isRegexWhitespace && !char.isWhitespace()) lastContentEnd = result.length
                beforeEnds[index + 1] = lastContentEnd
            }
            normalized = result.toString()
            contentEnd = lastContentEnd

            var normalizedOffset = normalized.length
            var nextContentStart = normalized.length
            afterStarts[length] = nextContentStart
            for (index in length - 1 downTo 0) {
                val char = raw[index]
                if (!regexWhitespace[index] || index == length - 1 || !regexWhitespace[index + 1]) {
                    normalizedOffset -= 1
                }
                if (!regexWhitespace[index] && !char.isWhitespace()) nextContentStart = normalizedOffset
                afterStarts[index] = nextContentStart
            }
            firstContent = afterStarts[0]
        }

        fun before(sentence: SpeechSentence): String =
            beforeWindow(normalized, firstContent, beforeEnds[beforeLength + sentence.startOffset])

        fun after(sentence: SpeechSentence): String =
            afterWindow(normalized, afterStarts[beforeLength + sentence.endOffset], contentEnd)
    }

    private fun beforeWindow(normalized: String, start: Int, end: Int): String {
        if (end <= start) return ""
        val window = normalized.substring(maxOf(start, end - CONTEXT_LENGTH), end)
        // Matcher normalizes/trims again before takeLast(160). Keep a boundary whitespace inside
        // that window; this extra character is discarded and never becomes navigation text.
        return if (window.first().isWhitespace()) "x$window" else window
    }

    private fun afterWindow(normalized: String, start: Int, end: Int): String {
        if (end <= start) return ""
        val window = normalized.substring(start, minOf(end, start + CONTEXT_LENGTH))
        return if (window.last().isWhitespace()) "${window}x" else window
    }

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()
}
