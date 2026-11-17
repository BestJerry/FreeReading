package com.freereading.reader

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderContentsTest {
    @Test fun flatteningKeepsNestedPublicationOrderAndSiblingFallbackTitles() {
        val entries = flattenContents(listOf(
            link("part.xhtml", "第一部", listOf(
                link("one.xhtml", "第一章"),
                link("two.xhtml", null, listOf(link("two.xhtml#end", "尾声"))),
            )),
            link("three.xhtml", null),
        ))

        assertEquals(listOf("第一部", "第一章", null, "尾声", null), entries.map { it.link.title })
        assertEquals(listOf(0, 1, 1, 2, 0), entries.map { it.depth })
        assertEquals(listOf("第 1 节", "第 1 节", "第 2 节", "第 1 节", "第 2 节"),
            entries.map { it.fallbackTitle })
    }

    @Test fun readingOrderFallbackCanLocateTheCurrentResourceWithoutATableOfContents() {
        val readingOrder = (1..200).map { link("Text/chapter$it.xhtml") }
        val entries = flattenContents(readingOrder)

        assertEquals(174, currentContentsIndex(entries, locator("Text/chapter175.xhtml")))
        assertEquals("第 175 节", entries[174].fallbackTitle)
    }

    @Test fun repeatedTitlesInOtherResourcesCannotOverrideTheCurrentHref() {
        val entries = flattenContents(listOf(
            link("chapter1.xhtml", "序章"),
            link("chapter2.xhtml", "序章"),
            link("chapter3.xhtml", "序章"),
        ))

        assertEquals(1, currentContentsIndex(entries, locator("chapter2.xhtml", title = "序章")))
        assertNull(currentContentsIndex(entries, locator("missing.xhtml", title = "序章")))
    }

    @Test fun aDecodedLocationsFragmentMatchesAnEncodedTocFragmentExactlyOnce() {
        val entries = flattenContents(listOf(
            link("Text/book.xhtml#%E7%AC%AC%E4%B8%80%E7%AB%A0", "第一章"),
            link("Text/book.xhtml#%E7%AC%AC%E4%BA%8C%E7%AB%A0", "第二章"),
        ))

        assertEquals("第二章", contentsFragment(entries[1]))
        assertEquals(1, currentContentsIndex(entries, locator("Text/book.xhtml", fragments = listOf("第二章"))))
    }

    @Test fun fragmentsContainingAnEncodedPercentAreNotDecodedTwice() {
        val entries = flattenContents(listOf(
            link("book.xhtml#name%2520with-percent", "百分号"),
            link("book.xhtml#name%20with-percent", "空格"),
        ))

        assertEquals("name%20with-percent", contentsFragment(entries[0]))
        assertEquals(0, currentContentsIndex(entries,
            locator("book.xhtml", fragments = listOf("name%20with-percent"))))
        assertEquals(1, currentContentsIndex(entries,
            locator("book.xhtml#name%20with-percent")))
    }

    @Test fun fragmentEvidenceWinsOverAMisleadingResourceTitle() {
        val entries = flattenContents(listOf(
            link("book.xhtml#one", "第一章"),
            link("book.xhtml#two", "第二章"),
        ))

        assertEquals(1, currentContentsIndex(entries,
            locator("book.xhtml", title = "第一章", fragments = listOf("two"))))
    }

    @Test fun aUniqueTitleOnlyDisambiguatesEntriesInTheMatchingResource() {
        val entries = flattenContents(listOf(
            link("other.xhtml#two", "第二章"),
            link("book.xhtml#one", "第一章"),
            link("book.xhtml#two", "第二章"),
        ))

        assertEquals(2, currentContentsIndex(entries, locator("book.xhtml", title = " 第二章 ")))
    }

    @Test fun repeatedTitlesWithinAResourceUseItsResourceStartAsTheFallback() {
        val entries = flattenContents(listOf(
            link("book.xhtml", "全篇"),
            link("book.xhtml#one", "正文"),
            link("book.xhtml#two", "正文"),
        ))

        assertEquals(0, currentContentsIndex(entries, locator("book.xhtml", title = "正文")))
    }

    @Test fun aDeepResourceStartAndFragmentAliasPreferTheMostSpecificEntry() {
        val entries = flattenContents(listOf(link("book.xhtml", "第一部", listOf(
            link("book.xhtml", "第一章", listOf(link("book.xhtml#one", "第一节"))),
            link("book.xhtml#one", "同一节的其他入口"),
        ))))

        assertEquals(1, currentContentsIndex(entries, locator("book.xhtml")))
        assertEquals(2, currentContentsIndex(entries, locator("book.xhtml", fragments = listOf("one"))))
    }

    @Test fun aliasesForOneFragmentChooseTheLeafEvenWhenTheResourceTitleNamesItsParent() {
        val entries = flattenContents(listOf(link("book.xhtml#one", "第一部", listOf(
            link("book.xhtml#one", "第一章", listOf(link("book.xhtml#one", "第一节"))),
            link("book.xhtml#one", "其他入口"),
        ))))

        assertEquals(2, currentContentsIndex(entries, locator("book.xhtml", title = "第一部")))
    }

    @Test fun aResourceWithOnlyFragmentsFallsBackToItsFirstEntryWithoutGuessingProgress() {
        val entries = flattenContents(listOf(
            link("other.xhtml", "其他章"),
            link("book.xhtml#one", "第一章"),
            link("book.xhtml#two", "第二章"),
        ))

        assertEquals(1, currentContentsIndex(entries, locator("book.xhtml", progression = 0.95)))
    }

    @Test fun normalizedPathsAndQueriesRemainPartOfTheResourceIdentity() {
        val entries = flattenContents(listOf(
            link("Text/./chapter%201.xhtml?edition=one#start", "一"),
            link("Text/chapter%201.xhtml?edition=two#start", "二"),
        ))

        assertTrue(sameContentsResource(entries[0], locator("Text/chapter%201.xhtml?edition=one")))
        assertFalse(sameContentsResource(entries[1], locator("Text/chapter%201.xhtml?edition=one")))
        assertEquals(1, currentContentsIndex(entries, locator("Text/chapter%201.xhtml?edition=two")))
    }

    @Test fun anEmptyFragmentMatchesTheResourceAndAnEncodedHashStaysInItsPath() {
        val entries = flattenContents(listOf(
            link("book.xhtml#", "正文"),
            link("book%23one.xhtml#chapter", "文件名含井号"),
        ))

        assertNull(contentsFragment(entries[0]))
        assertEquals(0, currentContentsIndex(entries, locator("book.xhtml")))
        assertEquals(1, currentContentsIndex(entries, locator("book%23one.xhtml")))
        assertNull(currentContentsIndex(entries, locator("book%2523one.xhtml")))
    }

    @Test fun identicalFilenamesAndAbsoluteHostsCannotMatchAnotherResource() {
        val entries = flattenContents(listOf(
            link("other/chapter.xhtml", "其他目录"),
            link("https://other.example/book/chapter.xhtml", "其他网站"),
        ))

        assertNull(currentContentsIndex(entries, locator("Text/chapter.xhtml")))
        assertNull(currentContentsIndex(entries, locator("https://example.test/book/chapter.xhtml")))
    }

    @Test fun emptyMissingAndFragmentOnlyResourcesFailWithoutCrashing() {
        val entries = flattenContents(listOf(link("#one", "缺少资源路径")))

        assertNull(currentContentsIndex(emptyList(), locator("book.xhtml")))
        assertNull(currentContentsIndex(entries, null))
        assertNull(currentContentsIndex(entries, locator("book.xhtml#one")))
        assertFalse(sameContentsResource(entries.single(), locator("#one")))
    }

    private fun link(href: String, title: String? = null, children: List<Link> = emptyList()) =
        Link(requireNotNull(Url(href)), mediaType = MediaType.XHTML, title = title, children = children)

    private fun locator(href: String, title: String? = null, fragments: List<String> = emptyList(),
                        progression: Double? = null) =
        Locator(requireNotNull(Url(href)), MediaType.XHTML, title = title,
            locations = Locator.Locations(fragments = fragments, progression = progression))
}
