package com.freereading.reader

/** Resolves text anchors within one reading-order resource, never across the whole book. */
internal object SentenceLocatorMatcher {
    private val whitespace = Regex("\\s+")

    data class Anchor(
        val index: Int,
        val selector: String?,
        val before: String,
        val text: String,
        val after: String,
    )

    fun find(candidates: List<Anchor>, target: Anchor): Int? {
        if (candidates.isEmpty()) return null
        val targetText = normalize(target.text)
        val targetBefore = normalize(target.before).takeLast(160)
        val targetAfter = normalize(target.after).take(160)
        val sameElement = target.selector?.takeIf(String::isNotBlank)?.let { selector ->
            candidates.filter { it.selector == selector }.takeIf(List<Anchor>::isNotEmpty)
        }
        // A selection can span several block elements. Text context is stronger evidence than
        // the visible page's CSS selector, which Readium also includes on selection locators.
        val textMatches = if (targetText.isNotEmpty()) {
            val contained = candidates.filter { normalize(it.text).contains(targetText) }
            if (contained.isNotEmpty()) contained else {
                val spanning = candidates.filter { candidate ->
                    normalize(candidate.text).let { it.isNotEmpty() && targetText.contains(it) }
                }
                // A visible paragraph or a long selection may cover several sentences. Start
                // with its first matching sentence instead of a later sentence with more context.
                val firstOffset = spanning.minOfOrNull { targetText.indexOf(normalize(it.text)) }
                spanning.filter { targetText.indexOf(normalize(it.text)) == firstOffset }
                    .takeIf(List<Anchor>::isNotEmpty)
            }
        } else null
        // Page progression and ContentService progression use different denominators.
        // Without a text/element anchor there is no reliable sentence to return.
        val eligible = textMatches ?: sameElement ?: return null
        return eligible.maxByOrNull { candidate ->
            val before = normalize(candidate.before).takeLast(160)
            val after = normalize(candidate.after).take(160)
            val text = normalize(candidate.text)
            val contextScore = when {
                targetText.isNotEmpty() && text.contains(targetText) -> {
                    // Selection context is relative to the selected words, not the whole
                    // sentence. Include the sentence's own prefix/suffix when comparing it.
                    occurrences(text, targetText).maxOf { start ->
                        commonSuffix(before + text.take(start), targetBefore) +
                            commonPrefix(text.drop(start + targetText.length) + after, targetAfter)
                    }
                }
                text.isNotEmpty() && targetText.contains(text) -> {
                    occurrences(targetText, text).maxOf { start ->
                        commonSuffix(before, targetBefore + targetText.take(start)) +
                            commonPrefix(after, targetText.drop(start + text.length) + targetAfter)
                    }
                }
                else -> commonSuffix(before, targetBefore) + commonPrefix(after, targetAfter)
            }
            val textScore = when {
                targetText.isEmpty() -> 0
                text == targetText -> 200
                text.contains(targetText) -> 160
                targetText.contains(text) && text.isNotEmpty() -> 120
                else -> 0
            }
            val selectorScore = if (target.selector != null && candidate.selector == target.selector) 80 else 0
            // Favor the start of a block when the target is the entire visible paragraph.
            contextScore * 10.0 + textScore + selectorScore - candidate.index * 0.000001
        }?.index
    }

    private fun normalize(value: String): String = value.replace(whitespace, " ").trim()

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
