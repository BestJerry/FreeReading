package com.freereading.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import com.freereading.epub.BookImportException
import com.freereading.epub.ReadiumEngine
import com.freereading.epub.SentenceIndexer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.use

/** One instance per application. Original books and speech indexes live in private app storage. */
class BookRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "books")
    private val mutations = Mutex()
    private val progressLock = Any()
    private val progressCache = HashMap<String, ReadingProgress>()
    private val mutableBooks = MutableStateFlow<List<BookRecord>>(emptyList())
    val books: StateFlow<List<BookRecord>> = mutableBooks.asStateFlow()
    val readium = ReadiumEngine(appContext)

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutations.withLock {
            ensureRoot()
            root.listFiles().orEmpty().filter { it.name.startsWith(".import-") || it.name.startsWith(".trash-") }
                .forEach { it.deleteRecursively() }
            refreshLocked()
        }
    }

    suspend fun importBook(uri: Uri): BookRecord = withContext(Dispatchers.IO) {
        mutations.withLock {
            ensureRoot()
            val staging = File(root, ".import-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw BookImportException("无法创建书籍目录，请检查设备剩余空间")
            try {
                val stagedBook = File(staging, BOOK_FILE)
                val id = copyAndHash(uri, stagedBook)
                val destination = directory(id)
                if (destination.exists()) {
                    val existing = readRecord(destination)
                    if (!File(destination, BOOK_FILE).isFile || !File(destination, INDEX_FILE).isFile) {
                        throw BookImportException("已导入的书籍文件不完整，请移除后重新导入")
                    }
                    refreshLocked()
                    return@withLock existing
                }
                val publication = readium.open(stagedBook)
                val speechBook = try {
                    val sentences = SentenceIndexer().index(publication)
                    val hasCover = writeCover(publication, File(staging, COVER_FILE))
                    val metadata = publication.metadata
                    val book = BookRecord(
                        id = id,
                        title = metadata.title?.trim()?.takeIf { it.isNotEmpty() }?.take(2048)
                            ?: displayName(uri).substringBeforeLast('.').ifBlank { "EPUB" }.take(2048),
                        author = metadata.authors.joinToString("、") { it.name }.take(2048),
                        language = metadata.languages.firstOrNull().orEmpty().take(64),
                        addedAt = System.currentTimeMillis(),
                        sentenceCount = sentences.size,
                        coverPath = if (hasCover) File(destination, COVER_FILE).absolutePath else null,
                    )
                    SpeechBook(book, sentences)
                } finally {
                    publication.close()
                }
                writeIndex(File(staging, INDEX_FILE), speechBook)
                writeRecord(File(staging, METADATA_FILE), speechBook.book)
                currentCoroutineContext().ensureActive()
                if (!staging.renameTo(destination)) throw BookImportException("无法保存书籍，请检查设备剩余空间")
                refreshLocked()
                speechBook.book
            } catch (error: CancellationException) {
                throw error
            } catch (error: BookImportException) {
                throw error
            } catch (error: Exception) {
                throw BookImportException("导入失败：文件无法读取、内容损坏或设备空间不足", error)
            } finally {
                // Also runs on cancellation; a committed directory has already moved away.
                staging.deleteRecursively()
            }
        }
    }

    suspend fun loadSpeechBook(bookId: String): SpeechBook = withContext(Dispatchers.IO) {
        mutations.withLock {
            val dir = directory(bookId)
            try {
                val record = readRecord(dir)
                if (!File(dir, BOOK_FILE).isFile) throw BookImportException("书籍原文件已不存在，请重新导入")
                val sentences = readIndex(File(dir, INDEX_FILE), record)
                SpeechBook(record, sentences)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BookImportException) {
                throw error
            } catch (error: Exception) {
                throw BookImportException("听书索引无法读取，请移除书籍后重新导入", error)
            }
        }
    }

    fun bookFile(bookId: String): File = File(directory(bookId), BOOK_FILE)

    fun progress(bookId: String): ReadingProgress = synchronized(progressLock) {
        val dir = directory(bookId)
        if (!File(dir, METADATA_FILE).isFile) return@synchronized ReadingProgress(bookId)
        progressCache[bookId] ?: readProgress(dir, bookId).also { progressCache[bookId] = it }
    }

    fun saveProgress(progress: ReadingProgress): Unit = synchronized(progressLock) {
        val dir = directory(progress.bookId)
        // A delayed player/reader callback must never recreate a removed book.
        if (!dir.isDirectory || !File(dir, METADATA_FILE).isFile) return@synchronized
        val previous = progressCache[progress.bookId] ?: readProgress(dir, progress.bookId)
        if (progress.updatedAt < previous.updatedAt) return@synchronized
        val cleaned = progress.copy(
            sentenceIndex = progress.sentenceIndex.coerceAtLeast(0),
            audioOffsetMs = progress.audioOffsetMs.coerceAtLeast(0L),
        )
        progressCache[progress.bookId] = cleaned
        val json = JSONObject().apply {
            put("bookId", cleaned.bookId)
            put("locatorJson", cleaned.locatorJson)
            put("sentenceIndex", cleaned.sentenceIndex)
            put("audioOffsetMs", cleaned.audioOffsetMs)
            put("updatedAt", cleaned.updatedAt)
        }
        try {
            atomicWrite(File(dir, PROGRESS_FILE)) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        } catch (error: IOException) {
            // Playback stays usable if storage fills; retain the latest position in memory.
            Log.w(TAG, "Could not persist reading progress", error)
        }
    }

    suspend fun removeBook(bookId: String): Unit = withContext(Dispatchers.IO) {
        mutations.withLock {
            val dir = directory(bookId)
            val trash = File(root, ".trash-${UUID.randomUUID()}")
            synchronized(progressLock) {
                if (dir.exists() && !dir.renameTo(trash)) throw IOException("无法移除书籍，请稍后重试")
                progressCache.remove(bookId)
                mutableBooks.value = mutableBooks.value.filterNot { it.id == bookId }
            }
            // Rename is the commit point. Interrupted cleanup is retried by refresh().
            trash.deleteRecursively()
            File(appContext.cacheDir, "speech/$bookId").deleteRecursively()
            Unit
        }
    }

    private suspend fun copyAndHash(uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw BookImportException("无法打开所选文件，请重新选择 EPUB")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > ReadiumEngine.MAX_SOURCE_BYTES) throw BookImportException("书籍超过 100 MB，暂不支持导入")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                if (size == 0L) throw BookImportException("所选书籍文件为空")
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun displayName(uri: Uri): String = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { "EPUB" }
    } catch (_: Exception) {
        "EPUB"
    }

    private fun ensureRoot() {
        if (!root.isDirectory && !root.mkdirs()) throw BookImportException("无法创建书架目录，请检查设备剩余空间")
    }

    private fun directory(bookId: String): File {
        require(BOOK_ID.matches(bookId)) { "Invalid book ID" }
        return File(root, bookId)
    }

    private fun refreshLocked() {
        mutableBooks.value = root.listFiles().orEmpty()
            .filter { it.isDirectory && BOOK_ID.matches(it.name) }
            .mapNotNull { directory ->
                try {
                    readRecord(directory)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not read book metadata for ${directory.name}", error)
                    null
                }
            }
            .sortedByDescending { it.addedAt }
    }

    private fun readRecord(directory: File): BookRecord {
        val json = readSmallJson(File(directory, METADATA_FILE))
        val id = json.getString("id")
        if (id != directory.name || !BOOK_ID.matches(id) || json.getInt("version") != INDEX_VERSION) {
            throw BookImportException("书籍记录无效，请移除后重新导入")
        }
        val count = json.getInt("sentenceCount")
        if (count !in 1..100_000) throw BookImportException("书籍句子索引无效，请重新导入")
        return BookRecord(
            id = id,
            title = json.getString("title"),
            author = json.getString("author"),
            language = json.getString("language"),
            addedAt = json.getLong("addedAt"),
            sentenceCount = count,
            coverPath = File(directory, COVER_FILE).takeIf { it.isFile }?.absolutePath,
        )
    }

    private suspend fun writeCover(publication: Publication, file: File): Boolean {
        try {
            val link = (publication.readingOrder + publication.resources + publication.links)
                .firstOrNull { "cover" in it.rels } ?: return false
            val resource = publication.get(link) ?: return false
            val bytes = resource.use { it.read().getOrNull() } ?: return false
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            try {
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw IOException("Cover encoding failed")
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not create optional book cover", error)
            file.delete()
            return false
        } catch (_: OutOfMemoryError) {
            file.delete()
            return false
        }
    }

    private fun writeRecord(file: File, book: BookRecord) {
        val json = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("id", book.id)
            put("title", book.title)
            put("author", book.author)
            put("language", book.language)
            put("addedAt", book.addedAt)
            put("sentenceCount", book.sentenceCount)
        }
        atomicWrite(file) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    private suspend fun writeIndex(file: File, speechBook: SpeechBook) {
        atomicWrite(file) { output ->
            val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
            writer.beginObject().name("version").value(INDEX_VERSION)
            writer.name("bookId").value(speechBook.book.id)
            writer.name("sentences").beginArray()
            for (sentence in speechBook.sentences) {
                currentCoroutineContext().ensureActive()
                writer.beginObject()
                writer.name("index").value(sentence.index)
                writer.name("text").value(sentence.text)
                writer.name("locatorJson").value(sentence.locatorJson)
                writer.name("chapterIndex").value(sentence.chapterIndex)
                writer.name("chapterTitle").value(sentence.chapterTitle)
                writer.endObject()
            }
            writer.endArray().endObject()
            writer.flush()
        }
    }

    private suspend fun readIndex(file: File, record: BookRecord): List<SpeechSentence> {
        val sentences = ArrayList<SpeechSentence>(record.sentenceCount)
        var version = -1
        var bookId: String? = null
        var foundSentences = false
        AtomicFile(file).openRead().use { input ->
            if (input.channel.size() > 128L * 1024L * 1024L) throw IOException("Speech index is too large")
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt()
                        "bookId" -> bookId = reader.nextString()
                        "sentences" -> {
                            if (foundSentences) throw IOException("Duplicate sentence index")
                            foundSentences = true
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (sentences.size >= record.sentenceCount) throw IOException("Unexpected sentence count")
                                sentences.add(readSentence(reader, sentences.size))
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        if (version != INDEX_VERSION || bookId != record.id || sentences.size != record.sentenceCount) {
            throw BookImportException("听书索引版本或内容不完整，请移除书籍后重新导入")
        }
        return sentences
    }

    private fun readSentence(reader: JsonReader, expectedIndex: Int): SpeechSentence {
        var index = -1
        var text: String? = null
        var locator: String? = null
        var chapter = -1
        var title = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "index" -> index = reader.nextInt()
                "text" -> text = reader.nextString()
                "locatorJson" -> locator = reader.nextString()
                "chapterIndex" -> chapter = reader.nextInt()
                "chapterTitle" -> title = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val sentenceText = text ?: throw IOException("Missing sentence text")
        val sentenceLocator = locator ?: throw IOException("Missing sentence locator")
        if (index != expectedIndex || chapter < 0 || sentenceText.isBlank() || sentenceText.length > 500 ||
            sentenceLocator.length > 131_072 || Locator.fromJSON(JSONObject(sentenceLocator)) == null
        ) {
            throw IOException("Invalid sentence index")
        }
        return SpeechSentence(index, sentenceText, sentenceLocator, chapter, title)
    }

    private fun readProgress(dir: File, bookId: String): ReadingProgress = try {
        val json = readSmallJson(File(dir, PROGRESS_FILE))
        if (json.getString("bookId") != bookId) throw IOException("Progress belongs to another book")
        val locator = if (json.isNull("locatorJson")) null else json.getString("locatorJson")
        if (locator != null && Locator.fromJSON(JSONObject(locator)) == null) throw IOException("Invalid saved locator")
        ReadingProgress(
            bookId = bookId,
            locatorJson = locator,
            sentenceIndex = json.getInt("sentenceIndex").coerceAtLeast(0),
            audioOffsetMs = json.getLong("audioOffsetMs").coerceAtLeast(0L),
            updatedAt = json.getLong("updatedAt"),
        )
    } catch (_: Exception) {
        // No saved progress, or an interrupted/corrupt small file: start safely from the beginning.
        ReadingProgress(bookId, updatedAt = 0L)
    }

    private fun readSmallJson(file: File): JSONObject = AtomicFile(file).openRead().use { input ->
        if (input.channel.size() > 262_144L) throw IOException("Metadata file is too large")
        val bytes = input.readBytes()
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private inline fun atomicWrite(file: File, write: (FileOutputStream) -> Unit) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            write(stream)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        private const val TAG = "BookRepository"
        private const val BOOK_FILE = "book.epub"
        private const val METADATA_FILE = "book.json"
        private const val INDEX_FILE = "speech.json"
        private const val PROGRESS_FILE = "progress.json"
        private const val COVER_FILE = "cover.jpg"
        private const val INDEX_VERSION = 1
        private val BOOK_ID = Regex("[a-f0-9]{64}")
    }
}
