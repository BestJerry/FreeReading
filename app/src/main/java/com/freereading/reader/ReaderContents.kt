package com.freereading.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/** 展平后的目录项；保留嵌套深度用于缩进，缺少书内标题时使用备用标题。 */
internal data class ContentsEntry(val link: Link, val depth: Int, val fallbackTitle: String)

/** 按原有顺序递归展开目录树，父项位于子项之前。 */
internal fun flattenContents(links: List<Link>, depth: Int = 0): List<ContentsEntry> =
    links.flatMapIndexed { index, link ->
        listOf(ContentsEntry(link, depth, "第 ${index + 1} 节")) + flattenContents(link.children, depth + 1)
    }

/** 先按资源、锚点和标题判断当前目录项；同资源内的 DOM 顺序由原文解析器补充。 */
internal fun currentContentsIndex(entries: List<ContentsEntry>, locator: Locator?): Int? {
    locator ?: return null
    val candidates = entries.indices.filter { sameContentsResource(entries[it], locator) }
    if (candidates.isEmpty()) return null

    // Readium 的 locations 保存已解码锚点，URL.fragment 也只解码一次，避免重复解码。
    val fragments = locator.locations.fragments.filter(String::isNotBlank).toSet() +
        listOfNotNull(locator.href.fragment)
    candidates.filter { contentsFragment(entries[it])?.let(fragments::contains) == true }
        .maxByOrNull { entries[it].depth }
        ?.let { return it }

    if (candidates.map { contentsFragment(entries[it]) }.distinct().size == 1) {
        return candidates.maxByOrNull { entries[it].depth }
    }

    locator.title?.trim()?.takeIf(String::isNotEmpty)?.let { title ->
        candidates.filter { entries[it].link.title?.trim() == title }
            .singleOrNull()?.let { return it }
    }

    // 多级标题可能指向同一资源起点，优先选取嵌套最深的目录项。
    return candidates.filter { contentsFragment(entries[it]) == null }
        .maxByOrNull { entries[it].depth }
        ?: candidates.first()
}

/** 比较移除锚点并规范化后的资源地址，避免锚点差异影响同一正文资源的比较。 */
internal fun sameContentsResource(entry: ContentsEntry, locator: Locator): Boolean {
    val entryResource = entry.urlOrNull()?.resourceOrNull() ?: return false
    return entryResource == locator.href.resourceOrNull()
}

internal fun contentsFragment(entry: ContentsEntry): String? =
    entry.urlOrNull()?.fragment?.takeIf(String::isNotBlank)

private fun ContentsEntry.urlOrNull(): Url? = runCatching { link.url() }.getOrNull()

private fun Url.resourceOrNull(): Url? =
    // 直接移除原始分隔符，兼容 removeFragment() 会保留的空锚点尾缀；
    // 百分号编码的 # 仍属于资源路径，不在这里截断。
    runCatching { Url(toString().substringBefore('#'))?.normalize() }.getOrNull()
