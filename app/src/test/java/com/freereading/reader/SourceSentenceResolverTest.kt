package com.freereading.reader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSentenceResolverTest {
    private fun candidate(index: Int, selector: String?, text: String = "正文。") =
        SentenceLocatorMatcher.Anchor(index, selector, "", text, "")

    private fun sourceSelector(html: String, selector: String): String =
        requireNotNull(Jsoup.parse(html).selectFirst(selector)).cssSelector()

    @Test fun sourceIdentityResolvesHeadingWhereTextAndSelectorStringsBothDiffer() {
        val html = "<h1 class='part'><span>第1</span>\n<span>部分</span></h1><p>正文。</p>"
        val candidates = listOf(candidate(12, sourceSelector(html, "h1"), "第1部分"))
        val visible = candidate(-1, ".part", "第1\n部分")

        // Readium Content skips the standalone whitespace node; the browser keeps it. Its
        // shortest selector and Jsoup's full selector still identify exactly the same h1.
        assertNull(SentenceLocatorMatcher.find(candidates, visible))
        assertEquals(12, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element(".part")))
    }

    @Test fun separatorsEmptyBlocksAndImagesAdvanceToFollowingIndexedText() {
        for (empty in listOf("<hr id='anchor'>", "<div id='anchor'></div>",
            "<div id='anchor'><img src='cover.png' alt='封面'></div>")) {
            val html = "<p id='before'>前文。</p>$empty<p id='after'>后文。</p>"
            val candidates = listOf(candidate(7, "#before", "前文。"), candidate(21, "#after", "后文。"))
            assertEquals(empty, 21,
                SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#anchor")))
        }
    }

    @Test fun kindlePartPageBreakResolvesToSentence175InThisResource() {
        val html = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <div xmlns="http://www.w3.org/1999/xhtml" style="page-break-after:always"></div>
            <h1 class="kindle-cn-heading-1">第1部分<br/>为什么是我<br/>了解你的疼痛</h1>
            <p>这是正文。</p>
            </body></html>
        """.trimIndent()
        val heading = sourceSelector(html, "h1")
        val paragraph = sourceSelector(html, "p")
        val candidates = listOf(candidate(175, heading, "第1部分"), candidate(176, heading, "为什么是我"),
            candidate(177, heading, "了解你的疼痛"), candidate(178, paragraph, "这是正文。"))
        assertNull(SentenceLocatorMatcher.find(candidates, candidate(-1, "div", "")))
        assertEquals(175, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("div")))
    }

    @Test fun escapedStyleSelectorAfterAudioDecorationStillResolvesTo175() {
        val html = "<div style='page-break-after:always'></div><h1 id='part'>第1部分</h1>"
        val candidates = listOf(candidate(175, "#part", "第1部分"))
        assertEquals(175, SourceSentenceResolver(html).resolve(candidates,
            SourceSpeechAnchor.Element("[style='page-break-after\\:always']")))
    }

    @Test fun escapedAttributeMissingOrAmbiguousStillReturnsNull() {
        val html = "<div style='page-break-after:always'></div><div style='page-break-after:always'></div><h1 id='part'>第1部分</h1>"
        val resolver = SourceSentenceResolver(html)
        val candidates = listOf(candidate(175, "#part", "第1部分"))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("[style='page-break-after\\:always']")))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("[style='missing\\:value']")))
    }

    @Test fun escapedIdentifiersAreNotRewrittenAsAttributeStrings() {
        val html = "<h1 id='part:one' class='part:two'>第1部分</h1><p id='partone'>其他。</p>"
        val candidates = listOf(candidate(175, "#part\\:one", "第1部分"), candidate(176, "#partone", "其他。"))
        val resolver = SourceSentenceResolver(html)
        assertEquals(175, resolver.resolve(candidates, SourceSpeechAnchor.Element("#part\\:one")))
        assertEquals(175, resolver.resolve(candidates, SourceSpeechAnchor.Element(".part\\:two")))
    }

    @Test fun escapedLiteralBackslashIsPreservedRatherThanRemovedGlobally() {
        val html = "<div data-value='a\\:b'></div><h1 id='literal'>字面反斜杠。</h1>" +
            "<div data-value='a:b'></div><h1 id='colon'>冒号。</h1>"
        val candidates = listOf(candidate(175, "#literal", "字面反斜杠。"), candidate(176, "#colon", "冒号。"))
        val resolver = SourceSentenceResolver(html)
        assertEquals(175, resolver.resolve(candidates, SourceSpeechAnchor.Element("[data-value='a\\\\:b']")))
        assertEquals(176, resolver.resolve(candidates, SourceSpeechAnchor.Element("[data-value='a\\:b']")))
    }

    @Test fun cssHexAndQuotedEscapesKeepTheirStringMeaning() {
        val colon = SourceSentenceResolver("<div data-value='a:b'></div><h1 id='part'>第1部分</h1>")
        val apostrophe = SourceSentenceResolver("<div data-value=\"can't\"></div><h1 id='part'>第1部分</h1>")
        val candidates = listOf(candidate(175, "#part", "第1部分"))
        assertEquals(175, colon.resolve(candidates, SourceSpeechAnchor.Element("[data-value='a\\3a b']")))
        assertEquals(175, apostrophe.resolve(candidates, SourceSpeechAnchor.Element("[data-value='can\\'t']")))
    }

    @Test fun invalidQuotedAttributeStringsCannotBecomeMatchingSelectors() {
        val resolver = SourceSentenceResolver("<div data-value=\"can't\"></div><h1 id='part'>第1部分</h1>")
        val candidates = listOf(candidate(175, "#part", "第1部分"))
        for (selector in listOf("[data-value='can't']", "[data-value='can\nt']", "[data-value='can't\\']")) {
            assertNull(selector, resolver.resolve(candidates, SourceSpeechAnchor.Element(selector)))
        }
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("[style='']")))
    }

    @Test fun inlineFragmentInTextBlockStartsAtThatParagraphsFirstSentence() {
        val html = "<p id='before'>前文。</p><p id='paragraph'>段首。<span id='middle'></span>段尾。</p>"
        val candidates = listOf(candidate(3, "#before", "前文。"),
            candidate(10, "#paragraph", "段首。"), candidate(11, "#paragraph", "段尾。"))
        assertEquals(10, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Fragment("middle")))
    }

    @Test fun emptyFragmentBetweenParagraphsDoesNotRewindToResourceStart() {
        val html = "<div><p id='before'>前文。</p><a id='part-two'></a><p id='after'>后文。</p></div>"
        val candidates = listOf(candidate(3, "#before", "前文。"), candidate(29, "#after", "后文。"))
        assertEquals(29, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Fragment("part-two")))
    }

    @Test fun nestedBlocksUseFirstIndexedSentenceInsideTarget() {
        val html = "<p id='before'>此前。</p><section id='part'><div><h1 id='title'>标题。</h1>" +
            "<p id='body'>正文。</p></div>结尾。</section>"
        val candidates = listOf(candidate(90, "#part", "结尾。"), candidate(8, "#before", "此前。"),
            candidate(23, "#body", "正文。"), candidate(22, "#title", "标题。"))
        assertEquals(22, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#part")))
    }

    @Test fun inlineElementResolvesContainingIndexedBlock() {
        val html = "<p id='paragraph'>段首。<span class='line'>下一行。</span></p>"
        val candidates = listOf(candidate(6, "#paragraph", "段首。"), candidate(7, "#paragraph", "下一行。"))
        assertEquals(6, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element(".line")))
    }

    @Test fun emptyVisibleInlineElementDoesNotRewindToItsParentsEarlierText() {
        for (empty in listOf("<span id='empty' style='display:block'></span>", "<img id='empty' src='x.png'>")) {
            val html = "<p id='before'>此前。$empty</p><p id='after'>后文。</p>"
            val candidates = listOf(candidate(14, "#before", "此前。"), candidate(31, "#after", "后文。"))
            assertEquals(empty, 31,
                SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#empty")))
        }
    }

    @Test fun fragmentOnMidResourceBlockStartsWithinThatBlock() {
        val html = "<p id='before'>前文。</p><section id='part-two'><h2 id='title'>第二部分。</h2><p id='after'>后文。</p></section>"
        val candidates = listOf(candidate(3, "#before", "前文。"), candidate(47, "#after", "后文。"),
            candidate(46, "#title", "第二部分。"))
        assertEquals(46, SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Fragment("part-two")))
    }

    @Test fun missingMalformedAndAmbiguousTargetsNeverFallBack() {
        val html = "<p id='first'>前文。</p><p>后文。</p><i id='duplicate'></i><i id='duplicate'></i>"
        val candidates = listOf(candidate(4, "#first", "前文。"))
        val resolver = SourceSentenceResolver(html)
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("#missing")))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("p[")))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Element("p")))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Fragment("missing")))
        assertNull(resolver.resolve(candidates, SourceSpeechAnchor.Fragment("duplicate")))
    }

    @Test fun emptyFragmentCannotUseTheDocumentsMissingIdAsAnAnchor() {
        val html = "<html id='html'><head id='head'></head><body id='body'><p id='p'>正文。</p></body></html>"
        assertNull(SourceSentenceResolver(html).resolve(listOf(candidate(8, "#p")), SourceSpeechAnchor.Fragment("")))
    }

    @Test fun noFollowingIndexedTextReturnsNull() {
        val html = "<p id='before'>前文。</p><hr id='end'><div><img src='cover.png'></div>"
        val candidates = listOf(candidate(7, "#before", "前文。"))
        assertNull(SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#end")))
    }

    @Test fun dividerWithTextOnBothSidesInSameParentCannotSkipOrRewindThatText() {
        val html = "<div id='paragraph'>前文。<hr id='divider'>后文。</div><p id='later'>更后的段落。</p>"
        val candidates = listOf(candidate(20, "#paragraph", "前文。"),
            candidate(21, "#paragraph", "后文。"), candidate(22, "#later", "更后的段落。"))
        assertNull(SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#divider")))
    }

    @Test fun invalidCandidateSelectorsAreIgnoredWithoutGuessingText() {
        val html = "<p id='target'>目标。</p>"
        val candidates = listOf(candidate(1, "#missing", "目标。"), candidate(2, "p[", "目标。"),
            candidate(3, null, "目标。"))
        assertNull(SourceSentenceResolver(html).resolve(candidates, SourceSpeechAnchor.Element("#target")))
    }

    @Test fun onlyExplicitResourceStartCanChooseFirstSentenceWithoutElementAnchor() {
        val resolver = SourceSentenceResolver("<p>内容。</p>")
        val candidates = listOf(candidate(21, null), candidate(18, null))
        assertEquals(18, resolver.resolve(candidates, SourceSpeechAnchor.ResourceStart))
        assertNull(resolver.resolve(emptyList(), SourceSpeechAnchor.ResourceStart))
    }
}
