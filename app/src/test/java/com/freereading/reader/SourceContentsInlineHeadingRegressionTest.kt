package com.freereading.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceContentsInlineHeadingRegressionTest {
    private val resolver = SourceSentenceResolver("""
        <body>
          <h1 id="one">第一章</h1><p>第一章正文</p>
          <h1 id="heading"><a id="two"></a><span>第二章</span></h1>
          <p id="following">第二章正文</p>
        </body>
    """.trimIndent())
    private val entries = listOf(0 to "one", 1 to "two")

    @Test fun aVisibleHeadingContainingItsLeadingInlineAnchorBelongsToTheNewChapter() {
        assertEquals(1, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun theFollowingParagraphUsesTheSameChapterBoundary() {
        assertEquals(1, resolver.resolveContents(entries, SourceSpeechAnchor.Element("#following")))
    }

    @Test fun nestedLeadingMarkersAndWhitespaceBelongToTheVisibleHeading() {
        val nested = SourceSentenceResolver("""
            <h1 id="one">第一章</h1>
            <h1 id="heading">
              <span><a id="two"></a></span><strong>第二章</strong>
            </h1>
        """.trimIndent())

        assertEquals(1, nested.resolveContents(entries, SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun anAnchorWrappingTheHeadingTextAlsoBelongsToTheNewChapter() {
        val wrapped = SourceSentenceResolver("<h1 id='one'>甲</h1><h1 id='heading'><a id='two'>乙</a></h1>")

        assertEquals(1, wrapped.resolveContents(entries, SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun nonbreakingAndFullWidthIndentationDoNotHideTheLeadingAnchor() {
        val indented = SourceSentenceResolver("<h1 id='one'>甲</h1><h1 id='heading'>&nbsp;　<a id='two'></a>乙</h1>")

        assertEquals(1, indented.resolveContents(entries, SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun aLaterMarkerInsideALongParagraphDoesNotSkipItsOpeningText() {
        val paragraph = SourceSentenceResolver("""
            <h1 id="one">第一章</h1>
            <p id="current">第一章结尾。<span><a id="two"></a>第二章开始。</span></p>
        """.trimIndent())

        assertEquals(0, paragraph.resolveContents(entries, SourceSpeechAnchor.Element("#current")))
    }

    @Test fun trailingHeadingMarkersDoNotMoveTheCurrentChapterForward() {
        val trailing = SourceSentenceResolver("<h1 id='one'>甲</h1><h1 id='heading'>甲的标题<a id='two'></a></h1>")

        assertEquals(0, trailing.resolveContents(entries, SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun multipleLeadingAliasesUseTheLastReachedBoundaryAndRemainUnique() {
        val aliases = SourceSentenceResolver("""
            <h1 id="one">第一章</h1>
            <h1 id="heading"><a id="part"></a><a id="two"></a>第二章</h1>
        """.trimIndent())

        assertEquals(2, aliases.resolveContents(listOf(0 to "one", 1 to "part", 2 to "two"),
            SourceSpeechAnchor.Element("#heading")))
    }

    @Test fun aTextlessContainerDoesNotCertifyItsDescendantBoundary() {
        val textless = SourceSentenceResolver("""
            <h1 id="one">第一章</h1>
            <div id="current"><img src="divider.png"><a id="two"></a></div>
        """.trimIndent())

        assertEquals(0, textless.resolveContents(entries, SourceSpeechAnchor.Element("#current")))
    }
}
