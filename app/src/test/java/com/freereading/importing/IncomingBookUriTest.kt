package com.freereading.importing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.freereading.ReadingApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class IncomingBookUriTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: ReadingApplication
    private lateinit var sharedRoot: File

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        sharedRoot = temporary.newFolder("shared-storage").canonicalFile
        ShadowEnvironment.setExternalStorageDirectory(sharedRoot.toPath())
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
    }

    @Test fun viewAcceptsGrantedContentUriWithoutAnExtension() {
        val uri = Uri.parse("content://books.provider/123")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(uri, IncomingBookUri.fromIntent(app, intent))
    }

    @Test fun sendAcceptsStreamAndSingleClipDataFallback() {
        val uri = Uri.parse("content://books.provider/document/123")
        val stream = Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val clip = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("book", uri)
        }
        assertEquals(uri, IncomingBookUri.fromIntent(app, stream))
        assertEquals(uri, IncomingBookUri.fromIntent(app, clip))
    }

    @Test fun sendDoesNotOpenAnArbitraryItemFromMultipleClips() {
        val clip = ClipData.newRawUri("books", Uri.parse("content://books.provider/1"))
        clip.addItem(ClipData.Item(Uri.parse("content://books.provider/2")))
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = clip }
        assertNull(IncomingBookUri.fromIntent(app, intent))
    }

    @Test fun wrongExtraTypeAndMissingDataAreRejectedWithoutCrashing() {
        val wrongText = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "content://books.provider/1")
        val wrongParcelable = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Intent())
        assertNull(IncomingBookUri.fromIntent(app, wrongText))
        assertNull(IncomingBookUri.fromIntent(app, wrongParcelable))
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW)))
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_SEND)))
    }

    @Test fun unsupportedActionsAndRemoteSchemesAreRejected() {
        val content = Uri.parse("content://books.provider/123")
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_EDIT, content)))
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, content)))
        for (scheme in listOf("http", "https", "ftp", "javascript")) {
            val uri = Uri.parse("$scheme://example.com/book.epub")
            assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, uri)))
            assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        }
    }

    @Test fun sharedExternalFileIsAcceptedButPrivateFilesAreRejected() {
        val book = File(sharedRoot, "Download/book.epub").apply {
            parentFile!!.mkdirs()
            writeText("format is validated by the repository")
        }
        val uri = Uri.fromFile(book)
        assertEquals(Uri.fromFile(book.canonicalFile), IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, uri)))
        for (privateRoot in listOf(app.filesDir, app.cacheDir)) {
            assertNull(IncomingBookUri.fromIntent(app,
                Intent(Intent.ACTION_VIEW, Uri.fromFile(File(privateRoot, "private.epub")))))
        }
    }

    @Test fun canonicalFileValidationRejectsTraversalAndLookalikeRootPrefixes() {
        val sibling = File(sharedRoot.parentFile, "${sharedRoot.name}-other/book.epub")
        val traversal = File(sharedRoot, "../private/book.epub")
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, Uri.fromFile(sibling))))
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, Uri.fromFile(traversal))))
    }

    @Test fun applicationSpecificExternalDirectoriesRequireContentSharing() {
        for (area in listOf("data", "obb")) {
            val privateFile = File(sharedRoot, "Android/$area/${app.packageName}/private.epub")
            assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, Uri.fromFile(privateFile))))
        }
    }

    @Test fun missingContentAuthorityAndRemoteFileAuthorityAreRejected() {
        val content = Uri.parse("content:///books/123")
        val remoteFile = Uri.Builder().scheme("file").authority("another-host")
            .path(File(sharedRoot, "Download/book.epub").path).build()
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, content)))
        assertNull(IncomingBookUri.fromIntent(app, Intent(Intent.ACTION_VIEW, remoteFile)))
    }
}
