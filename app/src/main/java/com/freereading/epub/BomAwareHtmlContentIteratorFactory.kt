@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.epub

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.PublicationServicesHolder
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.publication.services.content.iterators.ResourceContentIteratorFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.map

/** 只规范化正文迭代器读取到的 HTML BOM，保持导航器和原始 EPUB 文件不变。 */
internal class BomAwareHtmlContentIteratorFactory : ResourceContentIteratorFactory {
    private val delegate = HtmlResourceContentIterator.Factory()

    /** 仅接管 HTML／XHTML；移除开头的 UTF-8 BOM 后交由 Readium 的标准迭代器解析。 */
    override suspend fun create(
        manifest: Manifest,
        servicesHolder: PublicationServicesHolder,
        readingOrderIndex: Int,
        resource: Resource,
        mediaType: MediaType,
        locator: Locator,
    ): Content.Iterator? {
        if (!mediaType.matchesAny(MediaType.HTML, MediaType.XHTML)) return null
        val normalized = resource.map { bytes -> Try.success(bytes.withoutLeadingUtf8Bom()) }
        return delegate.create(manifest, servicesHolder, readingOrderIndex, normalized, mediaType, locator)
    }
}
