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

/** Normalize only the content iterator's read, leaving the navigator and original EPUB untouched. */
internal class BomAwareHtmlContentIteratorFactory : ResourceContentIteratorFactory {
    private val delegate = HtmlResourceContentIterator.Factory()

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
