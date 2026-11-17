package com.freereading.reader

/** 在单个正文资源内按文本和上下文匹配句子，禁止跨全书猜测位置。 */
internal object SentenceLocatorMatcher {
    private val whitespace = Regex("\\s+")

    /** 句子或目标选区的匹配证据；文本可按需生成，避免复制大章中每句的上下文。 */
    class Anchor(
        val index: Int,
        val selector: String?,
        private val content: Text,
    ) {
        /** 按匹配需要读取的正文与前后文，不承担导航定位的存储职责。 */
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

        /** 兼容旧定位或即时选区，直接持有已提取的固定文本。 */
        private class FixedText(
            override val before: String,
            override val text: String,
            override val after: String,
        ) : Text
    }

    /**
     * 综合文本、前后文和元素选择器返回句子编号；证据不足时返回 null。
     * 目标覆盖整段或多句时优先最早匹配句，不能用页面百分比替代正文锚点。
     */
    fun find(candidates: List<Anchor>, target: Anchor): Int? {
        if (candidates.isEmpty()) return null
        val targetText = normalize(target.text)
        val targetBefore = normalize(target.before).takeLast(160)
        val targetAfter = normalize(target.after).take(160)
        // 缓存重复短句及未命中结果，但限制容量，避免按整章保留映射。
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
        // 选区可能跨多个块元素，而 Readium 也会附带当前可见页的 CSS 选择器；
        // 因此文本上下文比该选择器更能证明实际选中的位置。
        val textMatches = if (targetText.isNotEmpty()) {
            val contained = candidates.filter { normalize(it.text).contains(targetText) }
            if (contained.isNotEmpty()) contained else {
                // 可见段落或长选区可能包含多句，应从首个匹配句开始，
                // 不能因后面的句子上下文更丰富而跳过段首。
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
        // 页面进度与 ContentService 正文进度的分母不同，
        // 没有文本或元素锚点时无法可靠换算句子位置。
        val eligible = textMatches ?: sameElement ?: return null
        return eligible.maxByOrNull { candidate ->
            val before = normalize(candidate.before).takeLast(160)
            val after = normalize(candidate.after).take(160)
            val text = normalize(candidate.text)
            val targetOffset = if (text.isNotEmpty() && text.length <= targetText.length) firstOffsetInTarget(text) else -1
            val contextScore = when {
                targetText.isNotEmpty() && text.contains(targetText) -> {
                    // 选区上下文相对于所选文字而非整句；比较时需要纳入句内的前后文本。
                    occurrences(text, targetText).maxOf { start ->
                        commonSuffix(before + text.take(start), targetBefore) +
                            commonPrefix(text.drop(start + targetText.length) + after, targetAfter)
                    }
                }
                targetOffset >= 0 -> {
                    // 跨句匹配阶段已选定段落中最早出现的位置，
                    // 若再比较后面的重复文本，可能错误偏向段落中部的句子。
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
            // 目标为完整可见段落时，用微小的编号权重优先选择段落开头。
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
