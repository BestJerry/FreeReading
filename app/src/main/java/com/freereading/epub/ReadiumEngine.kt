package com.freereading.epub

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.content.DefaultContentService
import org.readium.r2.shared.publication.services.content.contentServiceFactory
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

/** 书籍导入／打开时可向界面直接呈现的失败信息，并保留底层异常供诊断。 */
class BookImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 打开并校验本地、无 DRM 的可重排 EPUB，统一导入和阅读所需的 Readium 配置。
 * 成功返回的 Publication 由调用方负责关闭；失败与取消路径在内部释放已取得的资源。
 */
class ReadiumEngine(context: Context) {
    private val retriever = AssetRetriever(context.applicationContext.contentResolver, DefaultHttpClient())
    private val opener = PublicationOpener(EpubParser())

    /** 导入入口：检查压缩包、书籍属性及每个正文资源，可报告资源验证进度。 */
    suspend fun open(file: File, onProgress: ((Long, Long?) -> Unit)? = null): Publication =
        openValidated(file, onProgress, reading = false)

    /** 打开已导入的私有书籍；保留结构校验，但不重复逐章读取全文，降低冷打开等待。 */
    internal suspend fun openForReading(file: File): Publication =
        openValidated(file, onProgress = null, reading = true)

    /** 将文件打开工作移至 IO 线程，并处理跨调度器交付期间取消造成的资源泄漏。 */
    private suspend fun openValidated(file: File, onProgress: ((Long, Long?) -> Unit)?,
                                      reading: Boolean): Publication {
        var pending: Publication? = null
        try {
            return withContext(Dispatchers.IO) {
                openLocal(file, onProgress, reading).also { pending = it }
            }
        } catch (error: Throwable) {
            // 调度器切回调用方时可能收到取消，连已成功打开但尚未交付的对象也需要关闭。
            pending?.close()
            throw error
        }
    }

    /** 建立带 BOM 兼容内容服务的 Publication，校验失败时连同底层 asset 一并释放。 */
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun openLocal(file: File, onProgress: ((Long, Long?) -> Unit)?,
                                  reading: Boolean): Publication {
        onProgress?.invoke(0, null)
        validateArchive(file)
        currentCoroutineContext().ensureActive()
        val asset = retriever.retrieve(file).getOrElse {
            throw BookImportException("无法识别这本书，请选择有效的 EPUB 文件")
        }
        val publication = try {
            if (!asset.format.conformsTo(Specification.Epub)) {
                throw BookImportException("目前仅支持 EPUB 格式")
            }
            // Readium 3.1.2 未采用构造时的钩子，需要在每次打开时配置内容迭代器。
            opener.open(asset, allowUserInteraction = false, onCreatePublication = {
                servicesBuilder.contentServiceFactory = DefaultContentService.createFactory(
                    listOf(BomAwareHtmlContentIteratorFactory()))
            }).getOrElse {
                throw BookImportException("EPUB 内容损坏或缺少必要文件，无法打开")
            }
        } catch (error: Throwable) {
            asset.close()
            throw error
        }
        try {
            validatePublication(publication, onProgress, readAllChapters = !reading)
            if (reading) {
                // Readium Fragment 构造时会同步调用位置服务；先在 IO 线程填充其缓存，
                // 保持原有位置／进度算法，避免主线程首次遍历整书。
                publication.positionsByReadingOrder()
            }
            currentCoroutineContext().ensureActive()
            // Publication 持有 asset 的容器，直到调用方执行 Publication.close()。
            return publication
        } catch (error: Throwable) {
            publication.close()
            throw error
        }
    }

    /** 拒绝 DRM、字体混淆算法白名单之外的加密、固定版式和外部正文；导入时另检查正文可读取。 */
    private suspend fun validatePublication(publication: Publication, onProgress: ((Long, Long?) -> Unit)?,
                                            readAllChapters: Boolean) {
        if (publication.isProtected || publication.isRestricted) {
            throw BookImportException("暂不支持有 DRM 保护的 EPUB，请导入无 DRM 的书籍")
        }
        val fontObfuscation = setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )
        if ((publication.readingOrder + publication.resources).any { link ->
                val encryption = link.properties.encryption
                encryption != null && encryption.algorithm !in fontObfuscation
            }
        ) {
            throw BookImportException("暂不支持加密的 EPUB 内容")
        }
        if (publication.metadata.presentation.layout == EpubLayout.FIXED ||
            publication.readingOrder.any { it.properties.layout == EpubLayout.FIXED }
        ) {
            throw BookImportException("目前仅支持可重排文本 EPUB，暂不支持固定版式书籍")
        }
        if (publication.readingOrder.isEmpty()) {
            throw BookImportException("这本 EPUB 没有可阅读的章节")
        }
        if (publication.readingOrder.any { it.url() is AbsoluteUrl }) {
            throw BookImportException("这本 EPUB 的正文依赖外部资源，暂不支持导入")
        }
        if (!readAllChapters) return
        // Readium 内容迭代器会记录并跳过无法读取的章节；导入时提前完整检查，
        // 避免损坏文件静默生成缺章的听书索引。
        val total = publication.readingOrder.size.toLong()
        onProgress?.invoke(0, total)
        for ((index, link) in publication.readingOrder.withIndex()) {
            currentCoroutineContext().ensureActive()
            val resource = publication.get(link)
                ?: throw BookImportException("EPUB 缺少部分正文文件，无法完整导入")
            resource.use { opened ->
                opened.read().getOrElse {
                    throw BookImportException("EPUB 的部分章节无法读取，请更换完整文件")
                }
            }
            onProgress?.invoke(index + 1L, total)
        }
    }

    /** 打开前检查 ZIP 路径、重复文件、原文件大小及条目声明的解压容量，并确认必需的格式标识文件。 */
    private suspend fun validateArchive(file: File) {
        if (!file.isFile || file.length() == 0L) throw BookImportException("书籍文件为空或已不存在")
        if (file.length() > MAX_SOURCE_BYTES) throw BookImportException("书籍超过 100 MB，暂不支持导入")
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val names = HashSet<String>()
                var totalSize = 0L
                var count = 0
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    count += 1
                    if (count > 20_000) throw BookImportException("书籍包含的文件过多，暂不支持导入")
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || name.split('/').any { it == ".." } || !names.add(name)) {
                        throw BookImportException("EPUB 内部文件路径无效")
                    }
                    if (entry.isDirectory) continue
                    if (entry.size < 0L || entry.size > MAX_RESOURCE_BYTES) {
                        throw BookImportException("书籍中的单个资源超过 16 MB，暂不支持导入")
                    }
                    totalSize += entry.size
                    if (totalSize > MAX_UNPACKED_BYTES) {
                        throw BookImportException("书籍解压后的内容超过 256 MB，暂不支持导入")
                    }
                }
                val mimetype = zip.getEntry("mimetype")
                    ?: throw BookImportException("文件不是有效的 EPUB：缺少 mimetype")
                if (mimetype.size !in 20L..64L) throw BookImportException("EPUB 的 mimetype 内容无效")
                val type = zip.getInputStream(mimetype).use { input ->
                    input.readBytes().toString(Charsets.US_ASCII).trim()
                }
                if (type != "application/epub+zip") throw BookImportException("目前仅支持 EPUB 格式")
                if (zip.getEntry("META-INF/container.xml") == null) {
                    throw BookImportException("EPUB 缺少 container.xml，无法读取书籍")
                }
            }
        } catch (error: java.util.zip.ZipException) {
            throw BookImportException("EPUB 压缩包损坏或不是有效的 EPUB", error)
        }
    }

    companion object {
        internal const val MAX_SOURCE_BYTES: Long = 100L * 1024L * 1024L
        private const val MAX_RESOURCE_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_UNPACKED_BYTES: Long = 256L * 1024L * 1024L
    }
}
