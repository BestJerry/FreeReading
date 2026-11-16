package com.freereading.epub

import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import com.freereading.reader.SentenceLocatorMatcher
import com.freereading.reader.SpeechSentenceAnchors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.use

/** Rebase one persisted cursor after rebuilding an index with the BOM-safe content parser. */
internal object ReadingProgressRebaser {
    private val whitespace = Regex("\\s+")

    suspend fun rebase(publication: Publication, book: SpeechBook, saved: ReadingProgress,
                       legacySentence: SpeechSentence? = null): ReadingProgress =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (book.sentences.isEmpty()) return@withContext saved.copy(
                bookId = book.book.id, sentenceIndex = 0, audioOffsetMs = 0)
            val json = saved.locatorJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@withContext fallback(book, saved)
            val locator = runCatching { Locator.fromJSON(json) }.getOrNull()
                ?: return@withContext fallback(book, saved)
            val href = locator.href.removeFragment().removeQuery()
            val chapter = publication.readingOrder.indexOfFirst {
                it.url().removeFragment().removeQuery().isEquivalent(href)
            }
            if (chapter < 0) return@withContext fallback(book, saved)
            if (normalize(locator.text.highlight.orEmpty()).isEmpty()) {
                val evidence = legacySentence?.takeIf { it.index == saved.sentenceIndex && it.chapterIndex == chapter }
                    ?.let { runCatching { Locator.fromJSON(JSONObject(it.locatorJson)) }.getOrNull() }
                    ?.takeIf {
                        it.href.removeFragment().removeQuery().isEquivalent(href) &&
                            normalize(it.text.highlight.orEmpty()).isNotEmpty() &&
                            normalize(it.text.highlight.orEmpty()) == normalize(requireNotNull(legacySentence).text)
                    }
                if (evidence != null) {
                    // Reader progress stores a visual page and an independent speech index.
                    // Rebase that old sentence, then retain the exact visual page for navigation.
                    return@withContext rebase(publication, book,
                        saved.copy(locatorJson = evidence.toJSON().toString(), audioOffsetMs = 0))
                        .copy(locatorJson = saved.locatorJson, audioOffsetMs = 0)
                }
            }
            val sentences = book.sentences.filter { it.chapterIndex == chapter }
            if (sentences.isEmpty()) return@withContext fallback(book, saved, preserveLocator = true)
            val anchors = SpeechSentenceAnchors.build(sentences.asSequence())
            val html = readHtml(publication, chapter)
            if (html.isNullOrEmpty()) return@withContext moved(saved, sentences.first(), false, preserveLocator = true)

            val selector = (locator.locations["cssSelector"] as? String)?.takeIf(String::isNotBlank)
            // Keep only this small token from the legacy DOM. Do not retain two whole parsed
            // chapter documents while rebuilding a large book under the Android heap limit.
            val legacyTag = if (html.startsWith('\uFEFF') && selector != null) legacyTag(html, selector) else null
            currentCoroutineContext().ensureActive()
            val document = Parser.htmlParser().setTrackPosition(true).parseInput(html.withoutLeadingHtmlBom(), "")
            val direct = selector?.let { uniqueElement(document, it) }?.takeIf { it.isInBody(document) }
            val mapped = legacyTag?.let { tag ->
                document.getAllElements().singleOrNull {
                    val range = it.sourceRange()
                    range.isTracked && range.start().pos() == tag.position - 1 && it.normalName() == tag.name
                }
            }
            val oldHeadOnly = mapped != null && !mapped.isInBody(document)
            val mappedBody = mapped?.takeIf { it.isInBody(document) }
            val targetText = normalize(locator.text.highlight.orEmpty())
            if (targetText.isEmpty()) {
                // Reading saves a visual locator, independently from its speech sentence. It is
                // already in browser coordinates; never apply a BOM child-index shift to it.
                val first = direct?.let { scope(anchors, it).minByOrNull { anchor -> anchor.index } }
                    ?.index?.let { book.sentences.getOrNull(it) } ?: sentences.first()
                return@withContext moved(saved, first, false, preserveLocator = true)
            }

            val textJson = json.optJSONObject("text")
            val hasSourceContext = textJson?.let { it.has("before") || it.has("after") } == true
            val rawAfter = textJson?.takeIf { it.has("after") && !it.isNull("after") }?.optString("after")
            val scopes = if (hasSourceContext || saved.audioOffsetMs > 0) listOf(mappedBody, direct) else listOf(direct, mappedBody)
            for (element in scopes.filterNotNull().distinct()) {
                currentCoroutineContext().ensureActive()
                match(scope(anchors, element), locator, element.cssSelector(), rawAfter, book)?.let { result ->
                    return@withContext moved(saved, result.sentence, result.sameSentence && !oldHeadOnly)
                }
            }
            // A discarded head/title locator can still name a real body heading. Restrict this
            // fallback to the same resource, and never carry audio time from that synthetic title.
            match(anchors, locator, null, rawAfter, book)?.let { result ->
                return@withContext moved(saved, result.sentence, result.sameSentence && !oldHeadOnly)
            }
            moved(saved, sentences.first(), false)
        }

    private data class SourceTag(val position: Int, val name: String)
    private data class Match(val sentence: SpeechSentence, val sameSentence: Boolean)

    private fun legacyTag(html: String, selector: String): SourceTag? {
        val document = Parser.htmlParser().setTrackPosition(true).parseInput(html, "")
        val element = uniqueElement(document, selector) ?: return null
        val range = element.sourceRange()
        return if (range.isTracked) SourceTag(range.start().pos(), element.normalName()) else null
    }

    private suspend fun readHtml(publication: Publication, chapter: Int): String? = try {
        publication.get(publication.readingOrder[chapter])?.use { resource ->
            resource.read().getOrNull()?.decodeString()?.getOrNull()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun scope(anchors: List<SentenceLocatorMatcher.Anchor>, element: Element): List<SentenceLocatorMatcher.Anchor> {
        if (element.normalName() == "body") return anchors
        val selectors = element.getAllElements().mapTo(HashSet()) { it.cssSelector() }
        val inside = anchors.filter { it.selector in selectors }
        if (inside.isNotEmpty() || element.isBlock) return inside
        val parent = element.parents().firstOrNull { it.isBlock } ?: return emptyList()
        val parentSelector = parent.cssSelector()
        return anchors.filter { it.selector == parentSelector }
    }

    private fun match(
        candidates: List<SentenceLocatorMatcher.Anchor>,
        locator: Locator,
        selector: String?,
        rawAfter: String?,
        book: SpeechBook,
    ): Match? {
        val targetText = normalize(locator.text.highlight.orEmpty())
        val textMatches = candidates.filter { anchor ->
            normalize(anchor.text).let { it.isNotEmpty() && (it.contains(targetText) || targetText.contains(it)) }
        }
        if (textMatches.isEmpty()) return null
        // Full sentence locators retain the exact remaining source text, even if the matching
        // helper only needs 160 characters. Its length disambiguates repeated sentences cheaply.
        val exactRanges = if (rawAfter == null) emptyList() else textMatches.filter { anchor ->
            book.sentences.getOrNull(anchor.index)?.let { afterMatches(it, rawAfter) } == true
        }
        val eligible = exactRanges.takeIf { it.isNotEmpty() } ?: textMatches
        val target = SentenceLocatorMatcher.Anchor(-1, selector, locator.text.before.orEmpty(),
            locator.text.highlight.orEmpty(), locator.text.after.orEmpty())
        val index = SentenceLocatorMatcher.find(eligible, target) ?: return null
        val sentence = book.sentences.getOrNull(index) ?: return null
        val sameText = normalize(sentence.text) == targetText
        val verifiedRange = if (rawAfter != null) exactRanges.size == 1
            else textMatches.count { normalize(it.text) == targetText } == 1
        return Match(sentence, sameText && verifiedRange)
    }

    private fun afterMatches(sentence: SpeechSentence, after: String): Boolean {
        val source = sentence.source ?: return false
        val suffix = source.locator.text.after.orEmpty()
        val remaining = source.text.length - sentence.endOffset
        return after.length == remaining + suffix.length &&
            source.text.regionMatches(sentence.endOffset, after, 0, remaining) &&
            suffix.regionMatches(0, after, remaining, suffix.length)
    }

    private fun moved(saved: ReadingProgress, sentence: SpeechSentence, sameSentence: Boolean,
                      preserveLocator: Boolean = false): ReadingProgress = saved.copy(
        sentenceIndex = sentence.index,
        locatorJson = if (preserveLocator) saved.locatorJson else sentence.locatorJson,
        audioOffsetMs = if (sameSentence) saved.audioOffsetMs.coerceAtLeast(0) else 0,
    )

    private fun fallback(book: SpeechBook, saved: ReadingProgress, preserveLocator: Boolean = false): ReadingProgress =
        moved(saved, book.sentences[saved.sentenceIndex.coerceIn(book.sentences.indices)], false, preserveLocator)

    private fun uniqueElement(document: Document, selector: String): Element? =
        runCatching { document.select(selector).singleOrNull() }.getOrNull()

    private fun Element.isInBody(document: Document): Boolean = this === document.body() || parents().any { it === document.body() }
    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()
}
