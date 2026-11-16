package com.freereading.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import com.freereading.epub.BookImportException
import com.freereading.epub.ReadiumEngine
import com.freereading.epub.ReadingProgressRebaser
import com.freereading.epub.SentenceIndexer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
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
    private val indexStore = SpeechIndexStore()
    private val speechBookCache = HashMap<String, CachedSpeechBook>()
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

    suspend fun importBook(uri: Uri, onProgress: ((BookImportProgress) -> Unit)? = null): BookRecord = withContext(Dispatchers.IO) {
        val progress = onProgress?.let { ImportProgressReporter(it) }
        progress?.report(ImportStage.WAITING)
        mutations.withLock {
            ensureRoot()
            val staging = File(root, ".import-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw BookImportException("无法创建书籍目录，请检查设备剩余空间")
            try {
                val stagedBook = File(staging, BOOK_FILE)
                val id = copyAndHash(uri, stagedBook, progress)
                val destination = directory(id)
                if (destination.exists()) {
                    val existing = readRecord(destination)
                    val recoverableIndex = File(destination, INDEX_FILE).isFile ||
                        (readTextIndexVersion(destination) < TEXT_INDEX_VERSION && File(destination, LEGACY_INDEX_FILE).isFile)
                    if (!File(destination, BOOK_FILE).isFile || !recoverableIndex) {
                        throw BookImportException("已导入的书籍文件不完整，请移除后重新导入")
                    }
                    refreshLocked()
                    progress?.report(ImportStage.COMPLETED)
                    return@withLock existing
                }
                val publication = readium.open(stagedBook, progress?.let { reporter ->
                    { completed: Long, total: Long? -> reporter.report(ImportStage.VALIDATING, completed, total) }
                })
                val speechBook = try {
                    val sentences = SentenceIndexer().index(publication, progress?.let { reporter ->
                        { completed: Long, total: Long -> reporter.report(ImportStage.INDEXING, completed, total) }
                    })
                    progress?.report(ImportStage.SAVING)
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
                writeIndex(File(staging, INDEX_FILE), speechBook, progress?.let { reporter ->
                    { completed: Long, total: Long? -> reporter.report(ImportStage.SAVING, completed, total) }
                })
                writeRecord(File(staging, METADATA_FILE), speechBook.book)
                currentCoroutineContext().ensureActive()
                if (!staging.renameTo(destination)) throw BookImportException("无法保存书籍，请检查设备剩余空间")
                cacheBook(destination, speechBook)
                refreshLocked()
                progress?.report(ImportStage.COMPLETED)
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
                val textVersion = readTextIndexVersion(dir)
                if (textVersion > TEXT_INDEX_VERSION) throw BookImportException("书籍索引来自更新版本，请更新应用后打开")
                if (textVersion < TEXT_INDEX_VERSION) {
                    return@withLock try {
                        migrateTextIndex(dir, record)
                    } catch (error: BookImportException) {
                        throw error
                    } catch (error: IOException) {
                        throw BookImportException("书籍定位更新未完成，请检查设备剩余空间后重试", error)
                    }
                }
                deleteLegacyIndex(dir)
                val stamp = cacheStamp(dir)
                val cached = speechBookCache[bookId]
                if (stamp != null && cached?.record == record && cached.stamp == stamp) {
                    cached.reference.get()?.let { return@withLock it }
                }
                val sentences = readIndex(File(dir, INDEX_FILE), record)
                SpeechBook(record, sentences).also { cacheBook(dir, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BookImportException) {
                speechBookCache.remove(bookId)
                throw error
            } catch (error: Exception) {
                speechBookCache.remove(bookId)
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
        try {
            // A late pre-migration callback must not certify an old locator as already rebased.
            val textVersion = progressVersionForSave(dir)
            writeProgressRecord(dir, cleaned, textVersion)
        } catch (error: IOException) {
            // Playback stays usable if storage fills; retain the latest position in memory.
            Log.w(TAG, "Could not persist reading progress", error)
        }
    }

    private suspend fun migrateTextIndex(dir: File, record: BookRecord): SpeechBook {
        speechBookCache.remove(record.id)
        val publication = readium.open(File(dir, BOOK_FILE))
        val rebuilt = try {
            val sentences = SentenceIndexer().index(publication)
            val book = SpeechBook(record.copy(sentenceCount = sentences.size), sentences)
            // Keep the original index through retries and concurrent legacy progress saves.
            // Only one old sentence is streamed from it; the old book is never loaded in full.
            val legacyIndex = preserveLegacyIndex(dir)
            // Metadata is the final commit marker. An interrupted new index must never be read
            // against the old count: retries always rebuild from the unchanged EPUB first.
            writeIndex(File(dir, INDEX_FILE), book)
            while (true) {
                currentCoroutineContext().ensureActive()
                val checkpoint = synchronized(progressLock) { readMigrationCheckpoint(dir, record.id) }
                val rebased = when {
                    checkpoint == null -> ReadingProgress(record.id, updatedAt = 0L)
                    checkpoint.textIndexVersion == TEXT_INDEX_VERSION -> checkpoint.progress
                    else -> ReadingProgressRebaser.rebase(publication, book, checkpoint.progress,
                        legacySentence(legacyIndex, record, checkpoint.progress))
                }
                currentCoroutineContext().ensureActive()
                val committed = synchronized(progressLock) {
                    // Progress writes do not use the book mutex. Rebase again if a newer legacy
                    // cursor was written while the source text was being resolved.
                    if (readMigrationCheckpoint(dir, record.id) != checkpoint) false else {
                        writeProgressRecord(dir, rebased, TEXT_INDEX_VERSION)
                        progressCache[record.id] = rebased
                        writeRecord(File(dir, METADATA_FILE), book.book)
                        true
                    }
                }
                if (committed) break
            }
            book
        } finally {
            publication.close()
        }
        deleteLegacyIndex(dir)
        cacheBook(dir, rebuilt)
        refreshLocked()
        return rebuilt
    }

    private fun preserveLegacyIndex(dir: File): File? {
        val legacy = File(dir, LEGACY_INDEX_FILE)
        if (legacy.isFile) return legacy
        if (legacy.exists()) throw IOException("Cannot preserve the previous speech index")
        val index = File(dir, INDEX_FILE)
        if (!index.exists() && !File(index.path + ".bak").exists()) return null
        // Recover an interrupted AtomicFile write before moving its committed base file.
        AtomicFile(index).openRead().use { }
        if (!index.renameTo(legacy)) throw IOException("Cannot preserve the previous speech index")
        return legacy
    }

    private suspend fun legacySentence(file: File?, record: BookRecord, saved: ReadingProgress): SpeechSentence? {
        if (file == null) return null
        val locator = saved.locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: return null
        if (!locator.text.highlight.isNullOrBlank()) return null
        return try {
            // The count must match the old metadata: a previous implementation may already
            // have written a new index before its metadata commit was interrupted.
            indexStore.readSentence(file, record.id, record.sentenceCount, saved.sentenceIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not recover the previous speech sentence", error)
            null
        }
    }

    private fun deleteLegacyIndex(dir: File) {
        // Metadata2 has committed. Cleanup is optional and can be retried on the next load.
        AtomicFile(File(dir, LEGACY_INDEX_FILE)).delete()
    }

    suspend fun removeBook(bookId: String): Unit = withContext(Dispatchers.IO) {
        mutations.withLock {
            val dir = directory(bookId)
            val trash = File(root, ".trash-${UUID.randomUUID()}")
            synchronized(progressLock) {
                if (dir.exists() && !dir.renameTo(trash)) throw IOException("无法移除书籍，请稍后重试")
                progressCache.remove(bookId)
                speechBookCache.remove(bookId)
                mutableBooks.value = mutableBooks.value.filterNot { it.id == bookId }
            }
            // Rename is the commit point. Interrupted cleanup is retried by refresh().
            trash.deleteRecursively()
            File(appContext.cacheDir, "speech/$bookId").deleteRecursively()
            Unit
        }
    }

    private suspend fun copyAndHash(uri: Uri, target: File, progress: ImportProgressReporter?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = if (progress != null) sourceSize(uri) else null
        progress?.report(ImportStage.COPYING, 0, total)
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw BookImportException("无法打开所选文件，请重新选择 EPUB")
        var size = 0L
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > ReadiumEngine.MAX_SOURCE_BYTES) throw BookImportException("书籍超过 100 MB，暂不支持导入")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (total != null && size > total!!) total = null
                    // A provider's advertised size can be wrong; only EOF + sync finish copying.
                    if (total == null || size < total!!) progress?.report(ImportStage.COPYING, size, total)
                }
                output.fd.sync()
                if (size == 0L) throw BookImportException("所选书籍文件为空")
            }
        }
        progress?.report(ImportStage.COPYING, size, size)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun sourceSize(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf(File::isFile)?.length()?.takeIf { it > 0 }
        }
        val queried = try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            }?.takeIf { it > 0 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // Never open a provider stream just to measure it: remote/pipe sources may be one-shot.
        return queried
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
        speechBookCache.keys.retainAll(mutableBooks.value.map { it.id }.toSet())
    }

    private fun readRecord(directory: File): BookRecord {
        val json = readSmallJson(File(directory, METADATA_FILE))
        val id = json.getString("id")
        if (id != directory.name || !BOOK_ID.matches(id) || json.getInt("version") != BOOK_RECORD_VERSION) {
            throw BookImportException("书籍记录无效，请移除后重新导入")
        }
        val count = json.getInt("sentenceCount")
        // A legacy/interrupted index may have a stale count. Its EPUB will be reindexed before
        // any index allocation or sentence-count validation is attempted.
        if (json.optInt(TEXT_INDEX_VERSION_FIELD, 1) >= TEXT_INDEX_VERSION &&
            count !in 1..SpeechIndexLimits.MAX_SENTENCES) throw BookImportException("书籍句子索引无效，请重新导入")
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
            put("version", BOOK_RECORD_VERSION)
            put(TEXT_INDEX_VERSION_FIELD, TEXT_INDEX_VERSION)
            put("id", book.id)
            put("title", book.title)
            put("author", book.author)
            put("language", book.language)
            put("addedAt", book.addedAt)
            put("sentenceCount", book.sentenceCount)
        }
        atomicWrite(file) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    private suspend fun writeIndex(file: File, speechBook: SpeechBook, onProgress: ((Long, Long?) -> Unit)? = null) =
        indexStore.write(file, speechBook, onProgress)

    private suspend fun readIndex(file: File, record: BookRecord): List<SpeechSentence> =
        indexStore.read(file, record.id, record.sentenceCount)

    private data class FileStamp(val length: Long, val modified: Long)
    private data class CachedSpeechBook(
        val record: BookRecord,
        val stamp: List<FileStamp>,
        val reference: WeakReference<SpeechBook>,
    )

    private fun cacheStamp(dir: File): List<FileStamp>? {
        val files = listOf(BOOK_FILE, METADATA_FILE, INDEX_FILE).map { File(dir, it) }
        if (files.any { !it.isFile }) return null
        return files.map { FileStamp(it.length(), it.lastModified()) }
    }

    private fun cacheBook(dir: File, book: SpeechBook) {
        cacheStamp(dir)?.let { stamp ->
            speechBookCache[book.book.id] = CachedSpeechBook(book.book, stamp, WeakReference(book))
        }
    }

    private data class StoredProgress(val progress: ReadingProgress, val textIndexVersion: Int)

    private fun readProgress(dir: File, bookId: String): ReadingProgress =
        readStoredProgress(dir, bookId)?.progress ?: ReadingProgress(bookId, updatedAt = 0L)

    private fun readStoredProgress(dir: File, bookId: String): StoredProgress? = try {
        val json = readSmallJson(File(dir, PROGRESS_FILE))
        if (json.getString("bookId") != bookId) throw IOException("Progress belongs to another book")
        val locator = if (json.isNull("locatorJson")) null else json.getString("locatorJson")
        if (locator != null && Locator.fromJSON(JSONObject(locator)) == null) throw IOException("Invalid saved locator")
        val progress = ReadingProgress(
            bookId = bookId,
            locatorJson = locator,
            sentenceIndex = json.getInt("sentenceIndex").coerceAtLeast(0),
            audioOffsetMs = json.getLong("audioOffsetMs").coerceAtLeast(0L),
            updatedAt = json.getLong("updatedAt"),
        )
        StoredProgress(progress, json.optInt(TEXT_INDEX_VERSION_FIELD, 1))
    } catch (_: Exception) {
        // No saved progress, or an interrupted/corrupt small file: start safely from the beginning.
        null
    }

    private fun readTextIndexVersion(dir: File): Int =
        readSmallJson(File(dir, METADATA_FILE)).optInt(TEXT_INDEX_VERSION_FIELD, 1)

    private fun progressVersionForSave(dir: File): Int = try {
        readTextIndexVersion(dir).coerceIn(1, TEXT_INDEX_VERSION)
    } catch (error: Exception) {
        Log.w(TAG, "Could not read text index version; retaining a legacy progress marker", error)
        1
    }

    /** Must be called under progressLock; failed normal writes can still have a newer live cursor. */
    private fun readMigrationCheckpoint(dir: File, bookId: String): StoredProgress? {
        val stored = readStoredProgress(dir, bookId)
        val cached = progressCache[bookId]
        return if (cached != null && cached != stored?.progress &&
            cached.updatedAt >= (stored?.progress?.updatedAt ?: Long.MIN_VALUE)) {
            StoredProgress(cached, progressVersionForSave(dir))
        } else stored
    }

    /** Migration must propagate write failures; ordinary playback can keep its in-memory cursor. */
    private fun writeProgressRecord(dir: File, progress: ReadingProgress, textIndexVersion: Int) {
        val json = JSONObject().apply {
            put("bookId", progress.bookId)
            put("locatorJson", progress.locatorJson)
            put("sentenceIndex", progress.sentenceIndex)
            put("audioOffsetMs", progress.audioOffsetMs)
            put("updatedAt", progress.updatedAt)
            put(TEXT_INDEX_VERSION_FIELD, textIndexVersion)
        }
        atomicWrite(File(dir, PROGRESS_FILE)) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
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
            atomic.finishWriteChecked(stream)
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
        private const val LEGACY_INDEX_FILE = "speech-legacy.json"
        private const val PROGRESS_FILE = "progress.json"
        private const val COVER_FILE = "cover.jpg"
        private const val BOOK_RECORD_VERSION = 1
        private const val TEXT_INDEX_VERSION = 2
        private const val TEXT_INDEX_VERSION_FIELD = "textIndexVersion"
        private val BOOK_ID = Regex("[a-f0-9]{64}")
    }
}
