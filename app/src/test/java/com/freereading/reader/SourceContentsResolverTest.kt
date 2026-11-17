package com.freereading.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceContentsResolverTest {
    private val resolver = SourceSentenceResolver("""
        <body>
          <p id="preface">序言</p>
          <h1 id="chapter-z">第一章</h1><p id="first">第一章正文</p>
          <section id="chapter-a"><h1>第二章</h1><p id="second">第二章正文</p></section>
          <h1 id="章 三">第三章</h1><p id="third">第三章正文</p>
        </body>
    """.trimIndent())
    private val entries = listOf(0 to null, 1 to "chapter-z", 2 to "chapter-a", 3 to "章 三")

    @Test fun visibleTextBetweenBoundariesSelectsItsChapterInDocumentOrder() {
        assertEquals(2, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#second")))
        assertEquals(1, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#first")))
    }

    @Test fun theHeadingItselfAndItsDescendantsBelongToTheNewChapter() {
        assertEquals(2, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#chapter-a")))
        assertEquals(2, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#chapter-a > h1")))
        assertEquals(3, resolver.resolveContents(entries, SourceSpeechAnchor.Fragment("章 三")))
    }

    @Test fun openingBeforeTheFirstChapterUsesTheResourceEntry() {
        assertEquals(0, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#preface")))
        assertEquals(0, resolver.resolveContents(entries, SourceSpeechAnchor.ResourceStart))
        assertNull(resolver.resolveContents(entries.drop(1), SourceSpeechAnchor.Element("#preface")))
    }

    @Test fun missingOrAmbiguousVisibleElementsDoNotGuessAChapter() {
        assertNull(resolver.resolveContents(entries, SourceSpeechAnchor.Element("#missing")))
        assertNull(resolver.resolveContents(entries, SourceSpeechAnchor.Element("p")))
        assertNull(resolver.resolveContents(entries, SourceSpeechAnchor.Element("body")))
    }

    @Test fun duplicateDirectoryAliasesOfOneBoundarySelectTheLaterNestedEntry() {
        assertEquals(7, resolver.resolveContents(entries + (7 to "chapter-a"), SourceSpeechAnchor.Element("#second")))
    }

    @Test fun absentOrDuplicatedBoundaryIdsAreIgnored() {
        assertEquals(2, resolver.resolveContents(entries + (9 to "missing"), SourceSpeechAnchor.Element("#second")))
        val duplicate = SourceSentenceResolver("<h1 id='a'>甲</h1><h1 id='a'>乙</h1><p id='current'>正文</p>")
        assertEquals(0, duplicate.resolveContents(listOf(0 to null, 1 to "a"), SourceSpeechAnchor.Element("#current")))
    }
}
