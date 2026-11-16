package com.freereading.reader

/** Resolves text anchors within one reading-order resource, never across the whole book. */
internal object SentenceLocatorMatcher {
    private val whitespace = Regex("\\s+")

    class Anchor(
        val index: Int,
        val selector: String?,
        private val content: Text,
    ) {
        interface Text {
            val before: String
            val text: String
            val after: String
        }

        constructor(index: Int, selector: String?, before: String, text: String, after: String) :
            this(index, selector, FixedText(before, text, after))

        val before: String get() = content.before
        val text: String get() = content.text
        val after: String get() = content.after

        private class FixedText(
            override val before: String,
            override val text: String,
            override val after: String,
        ) : Text
    }

    fun find(candidates: List<Anchor>, target: Anchor): Int? {
        if (candidates.isEmpty()) return null
        val targetText = normalize(target.text)
        val targetBefore = normalize(target.before).takeLast(160)
        val targetAfter = normalize(target.after).take(160)
        // Cache repeated short sentences, including misses, without retaining a chapter-sized map.
        val targetOffsets = LinkedHashMap<String, Int>(256, 0.75f, true)
        fun firstOffsetInTarget(text: String): Int {
            targetOffsets[text]?.let { return it }
            val offset = targetText.indexOf(text)
            if (targetOffsets.size >= 256) {
                val oldest = targetOffsets.entries.iterator()
                oldest.next()
                oldest.remove()
            }
            targetOffsets[text] = offset
            return offset
        }
        val sameElement = target.selector?.takeIf(String::isNotBlank)?.let { selector ->
            candidates.filter { it.selector == selector }.takeIf(List<Anchor>::isNotEmpty)
        }
        // A selection can span several block elements. Text context is stronger evidence than
        // the visible page's CSS selector, which Readium also includes on selection locators.
        val textMatches = if (targetText.isNotEmpty()) {
            val contained = candidates.filter { normalize(it.text).contains(targetText) }
            if (contained.isNotEmpty()) contained else {
                // A visible paragraph or a long selection may cover several sentences. Start
                // with its first matching sentence instead of a later sentence with more context.
                val spanning = ArrayList<Anchor>()
                var firstOffset = Int.MAX_VALUE
                for (candidate in candidates) {
                    val text = normalize(candidate.text)
                    if (text.isEmpty()) continue
                    val offset = firstOffsetInTarget(text)
                    if (offset < 0 || offset > firstOffset) continue
                    if (offset < firstOffset) {
                        firstOffset = offset
                        spanning.clear()
                    }
                    spanning.add(candidate)
                }
                spanning.takeIf(List<Anchor>::isNotEmpty)
            }
        } else null
        // Page progression and ContentService progression use different denominators.
        // Without a text/element anchor there is no reliable sentence to return.
        val eligible = textMatches ?: sameElement ?: return null
        return eligible.maxByOrNull { candidate ->
            val before = normalize(candidate.before).takeLast(160)
            val after = normalize(candidate.after).take(160)
            val text = normalize(candidate.text)
            val targetOffset = if (text.isNotEmpty() && text.length <= targetText.length) firstOffsetInTarget(text) else -1
            val contextScore = when {
                targetText.isNotEmpty() && text.contains(targetText) -> {
                    // Selection context is relative to the selected words, not the whole
                    // sentence. Include the sentence's own prefix/suffix when comparing it.
                    occurrences(text, targetText).maxOf { start ->
                        commonSuffix(before + text.take(start), targetBefore) +
                            commonPrefix(text.drop(start + targetText.length) + after, targetAfter)
                    }
                }
                targetOffset >= 0 -> {
                    // The spanning pass selected the earliest occurrence in this paragraph.
                    // Comparing against every later repeat can incorrectly favor a middle sentence.
                    commonSuffix(before, precedingContext(targetBefore, targetText, targetOffset, before.length)) +
                        commonPrefix(after, followingContext(targetText, targetOffset + text.length, targetAfter, after.length))
                }
                else -> commonSuffix(before, targetBefore) + commonPrefix(after, targetAfter)
            }
            val textScore = when {
                targetText.isEmpty() -> 0
                text == targetText -> 200
                text.contains(targetText) -> 160
                targetOffset >= 0 -> 120
                else -> 0
            }
            val selectorScore = if (target.selector != null && candidate.selector == target.selector) 80 else 0
            // Favor the start of a block when the target is the entire visible paragraph.
            contextScore * 10.0 + textScore + selectorScore - candidate.index * 0.000001
        }?.index
    }

    private fun normalize(value: String): String = value.replace(whitespace, " ").trim()

    private fun precedingContext(prefix: String, text: String, end: Int, limit: Int): String {
        val fromText = minOf(end, limit)
        return prefix.takeLast(limit - fromText) + text.substring(end - fromText, end)
    }

    private fun followingContext(text: String, start: Int, suffix: String, limit: Int): String {
        val fromText = minOf(text.length - start, limit)
        return text.substring(start, start + fromText) + suffix.take(limit - fromText)
    }

    private fun occurrences(text: String, term: String): List<Int> = buildList {
        var start = text.indexOf(term)
        while (start >= 0) {
            add(start)
            start = text.indexOf(term, start + 1)
        }
    }

    private fun commonPrefix(first: String, second: String): Int {
        val limit = minOf(first.length, second.length)
        var index = 0
        while (index < limit && first[index] == second[index]) index += 1
        return index
    }

    private fun commonSuffix(first: String, second: String): Int =
        commonPrefix(first.reversed(), second.reversed())
}
