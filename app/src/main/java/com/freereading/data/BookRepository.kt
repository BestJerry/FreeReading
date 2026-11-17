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
import kotlinx.coroutines.Job
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

/**
 * 应用级本地书库，管理私有目录中的 EPUB、听书索引、封面和进度。
 * mutations 串行化导入／删除／索引迁移；progressLock 单独保护进度，避免播放保存被整书解析阻塞。
 */
class BookRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "books")
    private val mutations = Mutex()
    private val progressLock = Any()
    private val progressCache = HashMap<String, ReadingProgress>()
    private val readingSavesLock = Any()
    private val readingSaves = HashMap<String, MutableSet<PendingReadingSave>>()
    private val indexStore = SpeechIndexStore()
    private val speechBookCache = HashMap<String, CachedSpeechBook>()
    private val mutableBooks = MutableStateFlow<List<BookRecord>>(emptyList())
    val books: StateFlow<List<BookRecord>> = mutableBooks.asStateFlow()
    val readium = ReadiumEngine(appContext)

    /** 清理上次中断留下的暂存／回收目录，并重新发布可用的书架元数据。 */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutations.withLock {
            ensureRoot()
            root.listFiles().orEmpty().filter { it.name.startsWith(".import-") || it.name.startsWith(".trash-") }
                .forEach { it.deleteRecursively() }
            refreshLocked()
        }
    }

    /**
     * 在暂存目录内完成复制去重、EPUB 校验、分句和索引保存，最后重命名目录提交书籍。
     * 原文件哈希相同时复用已有记录；失败或取消会清理本次尚未提交的文件。
     */
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
                // 协程取消时也清理暂存目录；已提交的目录已通过重命名移到正式位置。
                staging.deleteRecursively()
            }
        }
    }

    /**
     * 为阅读首屏准备元数据和页面位置，新版听书索引保持按需解码。
     * 旧版定位必须先完成迁移；快速重开同书时先等待上一阅读页的最终保存。
     */
    suspend fun prepareReadingBook(bookId: String): ReadingBookBootstrap = withContext(Dispatchers.IO) {
        awaitReadingSaves(bookId)
        mutations.withLock {
            val dir = directory(bookId)
            try {
                val record = readRecord(dir)
                if (!File(dir, BOOK_FILE).isFile) throw BookImportException("书籍原文件已不存在，请重新导入")
                val textVersion = readTextIndexVersion(dir)
                if (textVersion > TEXT_INDEX_VERSION) throw BookImportException("书籍索引来自更新版本，请更新应用后打开")
                if (textVersion < TEXT_INDEX_VERSION) {
                    // 旧版页面／音频游标只有在定位索引迁移后才能安全使用。
                    val migrated = try {
                        migrateTextIndex(dir, record)
                    } catch (error: BookImportException) {
                        throw error
                    } catch (error: IOException) {
                        throw BookImportException("书籍定位更新未完成，请检查设备剩余空间后重试", error)
                    }
                    return@withLock ReadingBookBootstrap(migrated.book, progress(bookId), migrated)
                }
                // 恢复中断的原子写入，只检查文件大小，不扫描整份 JSON 或原文片段表。
                AtomicFile(File(dir, INDEX_FILE)).openRead().use { input ->
                    if (input.channel.size() !in 1L..SpeechIndexLimits.MAX_INDEX_BYTES) {
                        throw IOException("Speech index is empty or too large")
                    }
                }
                deleteLegacyIndex(dir)
                val stamp = cacheStamp(dir)
                val cached = speechBookCache[bookId]
                val full = if (stamp != null && cached?.record == record && cached.stamp == stamp) {
                    cached.reference.get()
                } else null
                if (full == null) speechBookCache.remove(bookId)
                ReadingBookBootstrap(record, progress(bookId), full)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BookImportException) {
                speechBookCache.remove(bookId)
                throw error
            } catch (error: Exception) {
                speechBookCache.remove(bookId)
                throw BookImportException("书籍读取未完成，请检查书籍文件后重试", error)
            }
        }
    }

    /** 登记阅读页的最终保存任务，使快速重开同书时能读取最新位置；按导入批次隔离旧任务。 */
    internal fun registerReadingSave(bookId: String, expectedAddedAt: Long, job: Job) {
        val pending = PendingReadingSave(expectedAddedAt, job)
        synchronized(readingSavesLock) {
            readingSaves.getOrPut(bookId) { HashSet() }.add(pending)
        }
        job.invokeOnCompletion {
            synchronized(readingSavesLock) {
                readingSaves[bookId]?.let { saves ->
                    saves.remove(pending)
                    if (saves.isEmpty()) readingSaves.remove(bookId)
                }
            }
        }
    }

    /** 只等待当前导入批次尚未结束的阅读保存任务，避免旧书任务阻塞重新导入后的新书。 */
    private suspend fun awaitReadingSaves(bookId: String) {
        while (true) {
            val pending = synchronized(readingSavesLock) { readingSaves[bookId]?.toList().orEmpty() }
            if (pending.isEmpty()) return
            val addedAt = try {
                synchronized(progressLock) { readRecord(directory(bookId)).addedAt }
            } catch (_: Exception) {
                // 元数据缺失或损坏交由正常准备流程统一报告。
                return
            }
            val sameGeneration = synchronized(readingSavesLock) {
                readingSaves[bookId].orEmpty().filter { it.expectedAddedAt == addedAt && !it.job.isCompleted }
            }
            if (sameGeneration.isEmpty()) return
            // 等待时不得持有书籍互斥锁、任务登记锁或进度锁：
            // 最终保存可能先调用 loadSpeechBook()，持锁等待会相互阻塞。
            sameGeneration.forEach { it.job.join() }
        }
    }

    /** 按需读取完整听书索引；文件指纹与元数据一致时复用弱引用缓存，旧索引先迁移。 */
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

    /** 在线程安全的进度缓存中读取最新位置，缺少合法进度时返回初始游标。 */
    fun progress(bookId: String): ReadingProgress = synchronized(progressLock) {
        val dir = directory(bookId)
        if (!File(dir, METADATA_FILE).isFile) return@synchronized ReadingProgress(bookId)
        progressCache[bookId] ?: readProgress(dir, bookId).also { progressCache[bookId] = it }
    }

    /**
     * 按更新时间保存较新的进度，并可校验导入批次，拒绝迟到写入。
     * 磁盘写入失败仍保留最新内存位置，不让当前播放因存储问题中断。
     */
    fun saveProgress(progress: ReadingProgress, expectedAddedAt: Long? = null): Unit = synchronized(progressLock) {
        val dir = directory(progress.bookId)
        // 拒绝迟到的播放／阅读回调，防止重新创建已经移除的书籍。
        if (!dir.isDirectory || !File(dir, METADATA_FILE).isFile) return@synchronized
        if (expectedAddedAt != null) {
            // 同一 EPUB 的哈希不变；用导入时间拒绝移除后重新导入前的旧保存任务。
            val currentAddedAt = try { readRecord(dir).addedAt } catch (_: Exception) { return@synchronized }
            if (currentAddedAt != expectedAddedAt) return@synchronized
        }
        val previous = progressCache[progress.bookId] ?: readProgress(dir, progress.bookId)
        if (progress.updatedAt < previous.updatedAt) return@synchronized
        val cleaned = progress.copy(
            sentenceIndex = progress.sentenceIndex.coerceAtLeast(0),
            audioOffsetMs = progress.audioOffsetMs.coerceAtLeast(0L),
        )
        progressCache[progress.bookId] = cleaned
        try {
            // 迁移前回调晚到时，不能把旧定位标记为已完成迁移。
            val textVersion = progressVersionForSave(dir)
            writeProgressRecord(dir, cleaned, textVersion)
        } catch (error: IOException) {
            // 空间不足时仍保留最新内存位置，避免影响当前阅读／播放。
            Log.w(TAG, "Could not persist reading progress", error)
        }
    }

    /**
     * 从原 EPUB 重建定位索引并迁移最新游标，依次提交索引、进度和元数据。
     * 元数据完成前保留旧索引；进度并发变化时重新定位，确保迁移中断后仍可重试。
     */
    private suspend fun migrateTextIndex(dir: File, record: BookRecord): SpeechBook {
        speechBookCache.remove(record.id)
        val publication = readium.open(File(dir, BOOK_FILE))
        val rebuilt = try {
            val sentences = SentenceIndexer().index(publication)
            val book = SpeechBook(record.copy(sentenceCount = sentences.size), sentences)
            // 重试和并发旧进度保存期间保留原索引。
            // 只流式提取迁移所需的一句，不把旧书的完整索引再次装入内存。
            val legacyIndex = preserveLegacyIndex(dir)
            // 元数据是迁移最终提交标记，不能用旧句数读取中断写入的新索引；
            // 重试时始终从未改变的 EPUB 重新建立索引。
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
                    // 进度写入不占用书籍互斥锁；解析原文期间若收到更新的旧版游标，
                    // 就重新定位，确保提交的是最新进度。
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

    /** 将旧索引保留为迁移证据；已有备份直接复用，避免重试时覆盖唯一的旧定位来源。 */
    private fun preserveLegacyIndex(dir: File): File? {
        val legacy = File(dir, LEGACY_INDEX_FILE)
        if (legacy.isFile) return legacy
        if (legacy.exists()) throw IOException("Cannot preserve the previous speech index")
        val index = File(dir, INDEX_FILE)
        if (!index.exists() && !File(index.path + ".bak").exists()) return null
        // 先恢复中断的原子写入，再移动已提交的基础文件以保留旧索引。
        AtomicFile(index).openRead().use { }
        if (!index.renameTo(legacy)) throw IOException("Cannot preserve the previous speech index")
        return legacy
    }

    /** 页面定位不含文本时，仅提取对应旧句作为迁移证据，避免加载整份旧索引。 */
    private suspend fun legacySentence(file: File?, record: BookRecord, saved: ReadingProgress): SpeechSentence? {
        if (file == null) return null
        val locator = saved.locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: return null
        if (!locator.text.highlight.isNullOrBlank()) return null
        return try {
            // 句数必须与旧元数据一致：旧实现可能在写好新索引后中断了元数据提交。
            indexStore.readSentence(file, record.id, record.sentenceCount, saved.sentenceIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not recover the previous speech sentence", error)
            null
        }
    }

    private fun deleteLegacyIndex(dir: File) {
        // 新版元数据已提交；旧索引清理失败时可在下次加载时重试。
        AtomicFile(File(dir, LEGACY_INDEX_FILE)).delete()
    }

    /** 先将书籍目录移入回收目录并清除内存记录，再清理文件与语音缓存。 */
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
            // 重命名即提交移除；之后清理中断可由 refresh() 重试。
            trash.deleteRecursively()
            File(appContext.cacheDir, "speech/$bookId").deleteRecursively()
            Unit
        }
    }

    /** 单次读取来源流完成复制和 SHA-256 去重标识计算，按实际字节限制文件大小。 */
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
                    // 来源声明的大小可能不准确，只有读到 EOF 且同步成功才表示复制完成。
                    if (total == null || size < total!!) progress?.report(ImportStage.COPYING, size, total)
                }
                output.fd.sync()
                if (size == 0L) throw BookImportException("所选书籍文件为空")
            }
        }
        progress?.report(ImportStage.COPYING, size, size)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** 仅查询来源元数据；大小未知或无法查询时返回 null，不为测量消耗输入流。 */
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
        // 不为测量大小额外打开输入流，远程或管道来源可能只允许读取一次。
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

    /** 在 mutations 保护下重建书架；坏元数据只跳过对应书籍，不阻塞其他书籍显示。 */
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

    /** 校验元数据版本、书籍标识和新版句数边界，封面路径由私有目录重新生成。 */
    private fun readRecord(directory: File): BookRecord {
        val json = readSmallJson(File(directory, METADATA_FILE))
        val id = json.getString("id")
        if (id != directory.name || !BOOK_ID.matches(id) || json.getInt("version") != BOOK_RECORD_VERSION) {
            throw BookImportException("书籍记录无效，请移除后重新导入")
        }
        val count = json.getInt("sentenceCount")
        // 旧版或中断迁移的句数可能过时；这类书会先从 EPUB 重建索引，
        // 不使用旧句数直接分配索引内存或进行新版句数校验。
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

    /** 尽量生成约 512 像素的采样封面；封面缺失、解码失败或内存不足不阻止导入。 */
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

    /** 用文件长度与修改时间判断听书内存缓存是否仍对应当前磁盘内容。 */
    private data class FileStamp(val length: Long, val modified: Long)
    /** 一次阅读页退出保存任务及其所属的书籍导入批次。 */
    private class PendingReadingSave(val expectedAddedAt: Long, val job: Job)
    /** 完整听书书籍的弱引用缓存，不强制长篇句子列表长期常驻内存。 */
    private data class CachedSpeechBook(
        val record: BookRecord,
        val stamp: List<FileStamp>,
        val reference: WeakReference<SpeechBook>,
    )

    /** 三个必要文件均存在时才生成缓存指纹，缺文件立即使缓存失效。 */
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

    /** 进度与其定位索引版本一起保存，迁移时据此区分新旧游标。 */
    private data class StoredProgress(val progress: ReadingProgress, val textIndexVersion: Int)

    private fun readProgress(dir: File, bookId: String): ReadingProgress =
        readStoredProgress(dir, bookId)?.progress ?: ReadingProgress(bookId, updatedAt = 0L)

    /** 读取并校验小型进度文件；无文件或损坏时返回 null，由调用方选择恢复策略。 */
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
        // 无进度或进度文件中断／损坏时返回 null，由调用方选择内存游标或初始位置。
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

    /** 必须在 progressLock 内调用；普通落盘失败时，内存中仍可能保存更新的有效游标。 */
    private fun readMigrationCheckpoint(dir: File, bookId: String): StoredProgress? {
        val stored = readStoredProgress(dir, bookId)
        val cached = progressCache[bookId]
        return if (cached != null && cached != stored?.progress &&
            cached.updatedAt >= (stored?.progress?.updatedAt ?: Long.MIN_VALUE)) {
            StoredProgress(cached, progressVersionForSave(dir))
        } else stored
    }

    /** 迁移写盘失败必须向上传播，不能宣告完成；普通播放保存则允许只保留内存游标。 */
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

    /** 小型元数据单独限长读取，避免异常文件作为整个 JSON 一次性占满内存。 */
    private fun readSmallJson(file: File): JSONObject = AtomicFile(file).openRead().use { input ->
        if (input.channel.size() > 262_144L) throw IOException("Metadata file is too large")
        val bytes = input.readBytes()
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    /** 原子写入小文件并确认提交；异常时调用 failWrite 清理未提交内容，再向上传播失败。 */
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
