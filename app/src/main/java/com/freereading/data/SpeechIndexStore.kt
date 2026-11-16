package com.freereading.data

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.freereading.epub.BookImportException
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** v2 stores original source locators once; sentence contexts are reconstructed only on demand. */
internal class SpeechIndexStore(private val maxIndexBytes: Long = SpeechIndexLimits.MAX_INDEX_BYTES) {
    init { require(maxIndexBytes in 1..SpeechIndexLimits.MAX_INDEX_BYTES) }

    suspend fun write(file: File, book: SpeechBook, onProgress: ((Long, Long?) -> Unit)? = null) {
        onProgress?.invoke(0, null)
        checkCount(book.sentences.size)
        if (book.book.sentenceCount != book.sentences.size) throw IOException("Unexpected sentence count")
        val sources = ArrayList<SpeechSource>()
        val sourceIds = IdentityHashMap<SpeechSource, Int>()
        val sentenceSources = IntArray(book.sentences.size)
        var textCharacters = 0L
        for ((index, sentence) in book.sentences.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (sentence.index != index) throw IOException("Invalid sentence order")
            val source = sentence.source ?: legacySource(sentence)
            validateRange(source, sentence.startOffset, sentence.endOffset)
            textCharacters += sentence.endOffset - sentence.startOffset
            checkTextBudget(textCharacters)
            sentenceSources[index] = sourceIds[source] ?: sources.size.also {
                sources.add(source)
                sourceIds[source] = it
            }
        }
        val total = sources.size.toLong() + book.sentences.size
        onProgress?.invoke(0, total)
        var completed = 0L
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val writer = JsonWriter(OutputStreamWriter(LimitedOutput(output, maxIndexBytes), Charsets.UTF_8))
            writer.beginObject().name("version").value(VERSION)
            writer.name("bookId").value(book.book.id)
            writer.name("sources").beginArray()
            var locatorCharacters = 0L
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                validateSource(source)
                val json = source.locator.toJSON().toString()
                if (json.length > MAX_LOCATOR_CHARACTERS) throw IOException("Source locator is too large")
                locatorCharacters += json.length
                checkLocatorBudget(locatorCharacters)
                writer.beginObject().name("locatorJson").value(json)
                writer.name("chapterIndex").value(source.chapterIndex)
                writer.name("chapterTitle").value(source.chapterTitle).endObject()
                completed += 1
                onProgress?.invoke(completed, total)
            }
            writer.endArray().name("sentences").beginArray()
            for ((index, sentence) in book.sentences.withIndex()) {
                currentCoroutineContext().ensureActive()
                writer.beginObject().name("index").value(index)
                writer.name("sourceIndex").value(sentenceSources[index])
                writer.name("startOffset").value(sentence.startOffset)
                writer.name("endOffset").value(sentence.endOffset).endObject()
                completed += 1
                // The last record can still be buffered or fail the byte limit during flush.
                if (completed < total) onProgress?.invoke(completed, total)
            }
            writer.endArray().endObject()
            writer.flush()
            atomic.finishWriteChecked(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        onProgress?.invoke(total, total)
    }

    suspend fun read(file: File, bookId: String, expectedCount: Int): List<SpeechSentence> {
        checkCount(expectedCount)
        var version = -1
        var actualBookId: String? = null
        var count = 0
        var rangeFormat: Boolean? = null
        val sources = ArrayList<SpeechSource>()
        val legacy = ArrayList<SpeechSentence>()
        val ranges = IntArray(expectedCount * 3)
        var textCharacters = 0L
        var locatorCharacters = 0L
        val rootFields = HashSet<String>()
        AtomicFile(file).openRead().use { input ->
            if (input.channel.size() > maxIndexBytes) throw IOException("Speech index is too large")
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!rootFields.add(name)) throw IOException("Duplicate index field")
                    when (name) {
                        "version" -> version = reader.nextInt()
                        "bookId" -> actualBookId = reader.nextString()
                        "sources" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (sources.size >= expectedCount) throw IOException("Too many sources")
                                val fields = readSource(reader)
                                if (fields.locatorJson.length > MAX_LOCATOR_CHARACTERS) throw IOException("Source locator is too large")
                                locatorCharacters += fields.locatorJson.length
                                checkLocatorBudget(locatorCharacters)
                                val locator = parseLocator(fields.locatorJson)
                                if (locator.text.highlight == null) throw IOException("Missing source text")
                                val source = SpeechSource(locator, fields.chapter, fields.title)
                                validateSource(source)
                                sources.add(source)
                            }
                            reader.endArray()
                        }
                        "sentences" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (count >= expectedCount) throw IOException("Unexpected sentence count")
                                val fields = readSentence(reader)
                                if (fields.index != count) throw IOException("Invalid sentence order")
                                val isRange = fields.sourceIndex != null
                                if (rangeFormat != null && rangeFormat != isRange) throw IOException("Mixed index formats")
                                rangeFormat = isRange
                                if (isRange) {
                                    if (fields.text != null || fields.locatorJson != null || fields.chapter != null ||
                                        fields.title != null) throw IOException("Mixed sentence fields")
                                    ranges[count * 3] = fields.sourceIndex!!
                                    ranges[count * 3 + 1] = fields.start ?: throw IOException("Missing range start")
                                    ranges[count * 3 + 2] = fields.end ?: throw IOException("Missing range end")
                                } else {
                                    if (fields.start != null || fields.end != null) throw IOException("Unexpected range")
                                    val text = fields.text ?: throw IOException("Missing sentence text")
                                    val json = fields.locatorJson ?: throw IOException("Missing sentence locator")
                                    val chapter = fields.chapter ?: throw IOException("Missing chapter")
                                    val title = fields.title.orEmpty()
                                    if (text.isBlank() || text.length > 500 || json.length > 131_072 ||
                                        chapter < 0 || title.length > 2048) throw IOException("Invalid legacy sentence")
                                    parseLocator(json)
                                    textCharacters += text.length
                                    locatorCharacters += json.length
                                    checkTextBudget(textCharacters)
                                    checkLocatorBudget(locatorCharacters)
                                    legacy.add(SpeechSentence(count, text, json, chapter, title))
                                }
                                count += 1
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing index content")
            }
        }
        if (actualBookId != bookId || count != expectedCount || "sentences" !in rootFields) {
            throw IOException("Index does not match book metadata")
        }
        if (version == 1 && rangeFormat == false && "sources" !in rootFields) return legacy
        if (version != VERSION || rangeFormat != true || sources.isEmpty()) throw IOException("Unsupported speech index")
        val sentences = ArrayList<SpeechSentence>(count)
        val used = BooleanArray(sources.size)
        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            val sourceIndex = ranges[index * 3]
            val source = sources.getOrNull(sourceIndex) ?: throw IOException("Invalid source reference")
            val start = ranges[index * 3 + 1]
            val end = ranges[index * 3 + 2]
            validateRange(source, start, end)
            textCharacters += end - start
            checkTextBudget(textCharacters)
            sentences.add(SpeechSentence(index, source, start, end))
            used[sourceIndex] = true
        }
        if (used.any { !it }) throw IOException("Unused source locator")
        return sentences
    }

    /** Read migration evidence without keeping the old index beside the rebuilt book in memory. */
    suspend fun readSentence(file: File, bookId: String, expectedCount: Int, index: Int): SpeechSentence {
        checkCount(expectedCount)
        if (index !in 0 until expectedCount) throw IOException("Invalid sentence index")
        val first = scanSentence(file, bookId, expectedCount, index)
        val fields = first.sentence
        if (first.version == 1) {
            val json = requireNotNull(fields.locatorJson)
            parseLocator(json)
            return SpeechSentence(index, requireNotNull(fields.text), json,
                requireNotNull(fields.chapter), fields.title.orEmpty())
        }
        // Sources normally precede ranges in v2. Scan again after finding the source ID,
        // retaining only that source even for a book with hundreds of thousands of sources.
        val second = scanSentence(file, bookId, expectedCount, index, requireNotNull(fields.sourceIndex))
        if (first.version != second.version || first.sourceCount != second.sourceCount ||
            fields != second.sentence) throw IOException("Speech index changed while reading")
        val sourceFields = second.source ?: throw IOException("Missing source locator")
        if (sourceFields.locatorJson.length > MAX_LOCATOR_CHARACTERS) throw IOException("Source locator is too large")
        val locator = parseLocator(sourceFields.locatorJson)
        if (locator.text.highlight == null) throw IOException("Missing source text")
        val source = SpeechSource(locator, sourceFields.chapter, sourceFields.title)
        validateSource(source)
        val start = requireNotNull(fields.start)
        val end = requireNotNull(fields.end)
        validateRange(source, start, end)
        return SpeechSentence(index, source, start, end)
    }

    private data class SentenceScan(
        val version: Int,
        val sourceCount: Int,
        val sentence: SentenceFields,
        val source: SourceFields?,
    )

    private suspend fun scanSentence(file: File, bookId: String, expectedCount: Int, index: Int,
                                     sourceToRead: Int? = null): SentenceScan {
        var version = -1
        var actualBookId: String? = null
        var count = 0
        var sourceCount = 0
        var rangeFormat: Boolean? = null
        var selected: SentenceFields? = null
        var source: SourceFields? = null
        var largestSource = -1
        var textCharacters = 0L
        var locatorCharacters = 0L
        val rootFields = HashSet<String>()
        AtomicFile(file).openRead().use { input ->
            if (input.channel.size() > maxIndexBytes) throw IOException("Speech index is too large")
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!rootFields.add(name)) throw IOException("Duplicate index field")
                    when (name) {
                        "version" -> version = reader.nextInt()
                        "bookId" -> actualBookId = reader.nextString()
                        "sources" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (sourceCount >= expectedCount) throw IOException("Too many sources")
                                if (sourceCount == sourceToRead) source = readSource(reader) else reader.skipValue()
                                sourceCount += 1
                            }
                            reader.endArray()
                        }
                        "sentences" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (count >= expectedCount) throw IOException("Unexpected sentence count")
                                val fields = readSentence(reader)
                                if (fields.index != count) throw IOException("Invalid sentence order")
                                val isRange = fields.sourceIndex != null
                                if (rangeFormat != null && rangeFormat != isRange) throw IOException("Mixed index formats")
                                rangeFormat = isRange
                                if (isRange) {
                                    if (fields.text != null || fields.locatorJson != null || fields.chapter != null ||
                                        fields.title != null) throw IOException("Mixed sentence fields")
                                    val start = fields.start ?: throw IOException("Missing range start")
                                    val end = fields.end ?: throw IOException("Missing range end")
                                    val sourceIndex = requireNotNull(fields.sourceIndex)
                                    if (sourceIndex < 0 || start < 0 || end <= start || end.toLong() - start > 500) {
                                        throw IOException("Invalid sentence range")
                                    }
                                    largestSource = maxOf(largestSource, sourceIndex)
                                    textCharacters += end - start
                                } else {
                                    if (fields.start != null || fields.end != null) throw IOException("Unexpected range")
                                    val text = fields.text ?: throw IOException("Missing sentence text")
                                    val json = fields.locatorJson ?: throw IOException("Missing sentence locator")
                                    val chapter = fields.chapter ?: throw IOException("Missing chapter")
                                    if (text.isBlank() || text.length > 500 || json.length > MAX_LOCATOR_CHARACTERS ||
                                        chapter < 0 || fields.title.orEmpty().length > 2048) throw IOException("Invalid legacy sentence")
                                    textCharacters += text.length
                                    locatorCharacters += json.length
                                    checkLocatorBudget(locatorCharacters)
                                }
                                checkTextBudget(textCharacters)
                                if (count == index) selected = fields
                                count += 1
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing index content")
            }
        }
        if (actualBookId != bookId || count != expectedCount || "sentences" !in rootFields) {
            throw IOException("Index does not match book metadata")
        }
        if (!(version == 1 && rangeFormat == false && "sources" !in rootFields) &&
            !(version == VERSION && rangeFormat == true && sourceCount > 0 && largestSource < sourceCount)) {
            throw IOException("Unsupported speech index")
        }
        return SentenceScan(version, sourceCount, selected ?: throw IOException("Missing sentence"), source)
    }

    private data class SourceFields(val locatorJson: String, val chapter: Int, val title: String)

    private fun readSource(reader: JsonReader): SourceFields {
        var json: String? = null
        var chapter = -1
        var title = ""
        val fields = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) throw IOException("Duplicate source field")
            when (name) {
                "locatorJson" -> json = reader.nextString()
                "chapterIndex" -> chapter = reader.nextInt()
                "chapterTitle" -> title = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return SourceFields(json ?: throw IOException("Missing source locator"), chapter, title)
    }

    private data class SentenceFields(
        var index: Int = -1, var sourceIndex: Int? = null, var start: Int? = null, var end: Int? = null,
        var text: String? = null, var locatorJson: String? = null, var chapter: Int? = null, var title: String? = null,
    )

    private fun readSentence(reader: JsonReader): SentenceFields {
        val result = SentenceFields()
        val fields = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) throw IOException("Duplicate sentence field")
            when (name) {
                "index" -> result.index = reader.nextInt()
                "sourceIndex" -> result.sourceIndex = reader.nextInt()
                "startOffset" -> result.start = reader.nextInt()
                "endOffset" -> result.end = reader.nextInt()
                "text" -> result.text = reader.nextString()
                "locatorJson" -> result.locatorJson = reader.nextString()
                "chapterIndex" -> result.chapter = reader.nextInt()
                "chapterTitle" -> result.title = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return result
    }

    private fun legacySource(sentence: SpeechSentence): SpeechSource {
        val locator = parseLocator(sentence.locatorJson)
        return SpeechSource(locator.copy(text = locator.text.copy(highlight = sentence.text)),
            sentence.chapterIndex, sentence.chapterTitle)
    }

    private fun parseLocator(json: String): Locator = try {
        Locator.fromJSON(JSONObject(json)) ?: throw IOException("Invalid speech locator")
    } catch (error: org.json.JSONException) {
        throw IOException("Invalid speech locator", error)
    }

    private fun validateSource(source: SpeechSource) {
        if (source.chapterIndex < 0 || source.chapterTitle.length > 2048 || source.text.isBlank() ||
            source.text.length > SpeechIndexLimits.MAX_BLOCK_CHARACTERS) throw IOException("Invalid speech source")
    }

    private fun validateRange(source: SpeechSource, start: Int, end: Int) {
        if (start < 0 || end <= start || end > source.text.length || end - start > 500 ||
            !isBoundary(source.text, start) || !isBoundary(source.text, end) ||
            source.text.substring(start, end).isBlank()) throw IOException("Invalid sentence range")
    }

    private fun isBoundary(text: String, offset: Int): Boolean = offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))

    private fun checkCount(count: Int) {
        if (count !in 1..SpeechIndexLimits.MAX_SENTENCES) throw IOException("Invalid sentence count")
    }

    private fun checkTextBudget(count: Long) {
        if (count > SpeechIndexLimits.MAX_TEXT_CHARACTERS) throw BookImportException("书籍正文超过可支持的容量")
    }

    private fun checkLocatorBudget(count: Long) {
        if (count > SpeechIndexLimits.MAX_SOURCE_LOCATOR_CHARACTERS) throw BookImportException("书籍原文位置索引超过可支持的容量")
    }

    private class LimitedOutput(private val output: OutputStream, private val limit: Long) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) {
            checkCapacity(1)
            output.write(value)
            count += 1
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkCapacity(length)
            output.write(bytes, offset, length)
            count += length
        }
        override fun flush() = output.flush()
        private fun checkCapacity(length: Int) {
            if (length.toLong() > limit - count) throw BookImportException("听书索引文件超过可支持的容量（128 MB）")
        }
    }

    companion object {
        const val VERSION: Int = 2
        private const val MAX_LOCATOR_CHARACTERS: Int = 131_072
    }
}
