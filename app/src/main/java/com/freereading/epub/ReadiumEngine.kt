package com.freereading.epub

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

class BookImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Opens only local, unprotected reflowable EPUBs. The caller owns the returned publication. */
class ReadiumEngine(context: Context) {
    private val retriever = AssetRetriever(context.applicationContext.contentResolver, DefaultHttpClient())
    private val opener = PublicationOpener(EpubParser())

    suspend fun open(file: File): Publication {
        var pending: Publication? = null
        try {
            return withContext(Dispatchers.IO) {
                openLocal(file).also { pending = it }
            }
        } catch (error: Throwable) {
            // Cancellation during the dispatcher handoff can discard a successfully opened value.
            pending?.close()
            throw error
        }
    }

    private suspend fun openLocal(file: File): Publication {
        validateArchive(file)
        currentCoroutineContext().ensureActive()
        val asset = retriever.retrieve(file).getOrElse {
            throw BookImportException("无法识别这本书，请选择有效的 EPUB 文件")
        }
        val publication = try {
            if (!asset.format.conformsTo(Specification.Epub)) {
                throw BookImportException("目前仅支持 EPUB 格式")
            }
            opener.open(asset, allowUserInteraction = false).getOrElse {
                throw BookImportException("EPUB 内容损坏或缺少必要文件，无法打开")
            }
        } catch (error: Throwable) {
            asset.close()
            throw error
        }
        try {
            validatePublication(publication)
            currentCoroutineContext().ensureActive()
            // Publication retains the asset's container until Publication.close().
            return publication
        } catch (error: Throwable) {
            publication.close()
            throw error
        }
    }

    private suspend fun validatePublication(publication: Publication) {
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
        // Readium's content iterator logs unreadable chapters and skips them. Check them here so
        // a damaged archive cannot silently import an incomplete speech index.
        for (link in publication.readingOrder) {
            currentCoroutineContext().ensureActive()
            val resource = publication.get(link)
                ?: throw BookImportException("EPUB 缺少部分正文文件，无法完整导入")
            resource.use { opened ->
                opened.read().getOrElse {
                    throw BookImportException("EPUB 的部分章节无法读取，请更换完整文件")
                }
            }
        }
    }

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
