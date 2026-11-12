package com.freereading.importing

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.freereading.ReadingApplication
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/** Real file import exercises the ViewModel, EPUB parser and repository as one request. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ExternalImportViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: ReadingApplication
    private lateinit var sharedRoot: File
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private lateinit var model: ExternalImportViewModel

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        sharedRoot = temporary.newFolder("shared-storage").canonicalFile
        ShadowEnvironment.setExternalStorageDirectory(sharedRoot.toPath())
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        store = ViewModelStore()
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
            ExternalImportViewModel::class.java
        ]
    }

    @After fun cleanUp() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun realExternalImportIsDeduplicatedAndOpenedOnlyOnce() = runTest(dispatcher) {
        val states = mutableListOf<ExternalImportState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.toList(states) }
        assertEquals(ExternalImportState.Ready, model.state.value)
        val intent = view(sampleFile("shared-book.epub"))
        val abandoned = File(app.filesDir, "books/.import-interrupted").apply { mkdirs() }
        File(abandoned, "book.epub").writeText("interrupted copy")

        model.accept(intent)
        model.accept(intent)
        val imported = awaitState { it is ExternalImportState.Imported } as ExternalImportState.Imported
        assertEquals("山间来信", app.repository.books.value.single().title)
        assertEquals(imported.bookId, app.repository.books.value.single().id)
        assertFalse(abandoned.exists())

        model.accept(intent)
        runCurrent()
        assertEquals(imported, model.state.value)
        model.markOpened("a-different-book")
        assertEquals(imported, model.state.value)

        model.markOpened(imported.bookId)
        assertEquals(ExternalImportState.Finished, model.state.value)
        model.accept(intent)
        model.markOpened(imported.bookId)
        runCurrent()
        assertEquals(ExternalImportState.Finished, model.state.value)
        assertEquals(1, states.filterIsInstance<ExternalImportState.Imported>().size)
        assertEquals(1, states.count { it == ExternalImportState.Importing })
        assertEquals(1, states.count { it == ExternalImportState.Finished })
    }

    @Test fun failedImportCanRetryTheSameUriAfterItsFileIsRepaired() = runTest(dispatcher) {
        val source = File(sharedRoot, "Download/retry.epub").apply {
            parentFile!!.mkdirs()
            writeText("broken EPUB")
        }
        model.accept(view(source))
        val failed = awaitState { it is ExternalImportState.Failed } as ExternalImportState.Failed
        assertTrue(failed.canRetry)
        assertTrue(app.repository.books.value.isEmpty())

        copySampleTo(source)
        model.retry()
        val imported = awaitState { it is ExternalImportState.Imported } as ExternalImportState.Imported
        assertEquals(imported.bookId, app.repository.books.value.single().id)
    }

    @Test fun unsupportedIntentFailsWithoutRetryAndDoesNotOpenABook() = runTest(dispatcher) {
        model.accept(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/book.epub")))
        val failed = awaitState { it is ExternalImportState.Failed } as ExternalImportState.Failed
        assertFalse(failed.canRetry)
        model.retry()
        model.markOpened("unrelated")
        runCurrent()
        assertEquals(failed, model.state.value)
        assertTrue(app.repository.books.value.isEmpty())
    }

    @Test fun latestDifferentUriWinsOverAnEarlierImport() = runTest(dispatcher) {
        val first = view(sampleFile("first.epub"))
        val lastFile = File(sharedRoot, "Download/latest.epub").apply {
            parentFile!!.mkdirs()
            writeText("the latest request is not an EPUB")
        }
        model.accept(first)
        runCurrent()
        model.accept(view(lastFile))
        val latestFailure = awaitState { it is ExternalImportState.Failed } as ExternalImportState.Failed
        assertTrue(latestFailure.canRetry)
        // The repository lock provides a completion barrier for any canceled first import.
        app.repository.refresh()
        runCurrent()
        assertEquals(latestFailure, model.state.value)
    }

    private suspend fun awaitState(predicate: (ExternalImportState) -> Boolean): ExternalImportState =
        withContext(Dispatchers.Default) {
            // Use real time for this bound: parser/file IO runs outside the test scheduler.
            withTimeout(10_000) { model.state.first(predicate) }
        }

    private fun sampleFile(name: String): File = File(sharedRoot, "Download/$name").also(::copySampleTo)

    private fun copySampleTo(target: File) {
        target.parentFile!!.mkdirs()
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun view(file: File) = Intent(Intent.ACTION_VIEW, Uri.fromFile(file))
        .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
