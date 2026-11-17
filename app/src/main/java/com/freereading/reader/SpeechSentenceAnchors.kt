package com.freereading.reader

import com.freereading.data.SpeechSentence
import com.freereading.data.SpeechSource
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** 构建轻量句子匹配证据；实际跳页仍使用句子的完整定位信息。 */
internal object SpeechSentenceAnchors {
    private const val CONTEXT_LENGTH = 160
    private val whitespace = Regex("\\s+")

    /** 兼容旧版完整定位与新版共享原段落索引，按需提取匹配上下文以控制大章内存。 */
    fun build(sentences: Sequence<SpeechSentence>): List<SentenceLocatorMatcher.Anchor> = buildList {
        // 大章中即使每句只复制 160 字上下文也可能耗尽堆内存，
        // 因此保留句子范围，仅为匹配器当前访问的原段落构建上下文。
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

    /** 将句子范围适配为延迟文本访问，避免提前复制前后文。 */
    private class RangeText(
        private val sentence: SpeechSentence,
        private val provider: ContextProvider,
    ) : SentenceLocatorMatcher.Anchor.Text {
        override val before: String get() = provider.context(requireNotNull(sentence.source)).before(sentence)
        override val text: String get() = sentence.text
        override val after: String get() = provider.context(requireNotNull(sentence.source)).after(sentence)
    }

    /** 只缓存最近访问的原段落，连续匹配共享段落的句子时可复用规范化结果。 */
    private class ContextProvider {
        private var current: Pair<SpeechSource, SourceContext>? = null

        fun context(source: SpeechSource): SourceContext {
            val cached = current
            if (cached?.first === source) return cached.second
            return SourceContext(source).also { current = source to it }
        }
    }

    /** 原段落规范化结果及偏移映射，让句子的 UTF-16 范围仍能对应到压缩空白后的上下文。 */
    private class SourceContext(private val source: SpeechSource) {
        private val beforeLength = source.locator.text.before.orEmpty().length
        private val length = beforeLength + source.text.length + source.locator.text.after.orEmpty().length
        private val beforeEnds = IntArray(length + 1)
        private val afterStarts = IntArray(length + 1)
        private val normalized: String
        private val firstContent: Int
        private val contentEnd: Int

        init {
            // 同一原段落的连续访问复用规范化结果；使用同一个实际正则匹配空白，
            // 保持 Android 与 JVM 对 Unicode 空白字符处理的各自一致性。
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
        // 匹配器会再次规范化并裁剪空白，再取最后 160 字；用占位符保护窗口边界空白，
        // 该额外字符之后会被截掉，不会进入真实导航文本。
        return if (window.first().isWhitespace()) "x$window" else window
    }

    private fun afterWindow(normalized: String, start: Int, end: Int): String {
        if (end <= start) return ""
        val window = normalized.substring(start, minOf(end, start + CONTEXT_LENGTH))
        return if (window.last().isWhitespace()) "${window}x" else window
    }

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()
}
