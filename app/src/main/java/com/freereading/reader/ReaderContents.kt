package com.freereading.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

internal data class ContentsEntry(val link: Link, val depth: Int, val fallbackTitle: String)

internal fun flattenContents(links: List<Link>, depth: Int = 0): List<ContentsEntry> =
    links.flatMapIndexed { index, link ->
        listOf(ContentsEntry(link, depth, "第 ${index + 1} 节")) + flattenContents(link.children, depth + 1)
    }

/** Resolves immediate locator evidence; resource-internal DOM ordering is handled separately. */
internal fun currentContentsIndex(entries: List<ContentsEntry>, locator: Locator?): Int? {
    locator ?: return null
    val candidates = entries.indices.filter { sameContentsResource(entries[it], locator) }
    if (candidates.isEmpty()) return null

    // Readium stores decoded fragments in locations; URL.fragment also decodes exactly once.
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

    // Several nested headings may target one resource start. Prefer the most specific one.
    return candidates.filter { contentsFragment(entries[it]) == null }
        .maxByOrNull { entries[it].depth }
        ?: candidates.first()
}

internal fun sameContentsResource(entry: ContentsEntry, locator: Locator): Boolean {
    val entryResource = entry.urlOrNull()?.resourceOrNull() ?: return false
    return entryResource == locator.href.resourceOrNull()
}

internal fun contentsFragment(entry: ContentsEntry): String? =
    entry.urlOrNull()?.fragment?.takeIf(String::isNotBlank)

private fun ContentsEntry.urlOrNull(): Url? = runCatching { link.url() }.getOrNull()

private fun Url.resourceOrNull(): Url? =
    // Removing the raw separator also handles trailing '#', which Readium's removeFragment()
    // retains when URL.fragment is blank. Percent-encoded '#' remains part of the resource.
    runCatching { Url(toString().substringBefore('#'))?.normalize() }.getOrNull()
