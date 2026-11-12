package com.freereading.importing

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.freereading.ReadingApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ExternalOpenManifestTest {
    @Test fun epubWithOpaqueContentUriIsOfferedInOpenWith() {
        assertTrue(resolves(view("content://files.example/document/123", "application/epub+zip")))
        assertTrue(resolves(view("content://files.example/document/123", "application/x-epub+zip")))
    }

    @Test fun genericTypeRequiresVisibleEpubExtension() {
        assertTrue(resolves(view("content://files.example/Download/book.epub", "application/octet-stream")))
        assertTrue(resolves(view("content://files.example/Download/book.EPUB", "application/zip")))
        assertTrue(resolves(view("content://files.example/Download/book.author.v1.epub", "application/octet-stream")))
        assertFalse(resolves(view("content://files.example/Download/archive.zip", "application/zip")))
        assertFalse(resolves(view("content://files.example/document/123", "application/octet-stream")))
        assertFalse(resolves(view("content://files.example/Download/data.bin", "application/octet-stream")))
    }

    @Test fun extensionCanOpenWithoutMimeButNetworkLinksAreNotClaimed() {
        assertTrue(resolves(view("file:///sdcard/Download/book.epub", null)))
        assertTrue(resolves(view("content://files.example/Download/book.epub", null)))
        assertFalse(resolves(view("https://files.example/book.epub", "application/epub+zip")))
    }

    @Test fun shareAcceptsOnlyEpubMimeTypes() {
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("application/epub+zip")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/zip")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/octet-stream")))
    }

    private fun view(url: String, mime: String?) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), mime)

    private fun resolves(intent: Intent): Boolean {
        val context = RuntimeEnvironment.getApplication()
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == ImportActivity::class.java.name }
    }
}
