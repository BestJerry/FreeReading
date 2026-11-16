package com.freereading

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.freereading.data.BookImportProgress
import com.freereading.data.ImportStage
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The real ViewModel imports real EPUBs; a repository lock controls the otherwise fast waiting phase. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class LibraryViewModelImportTest {
    private lateinit var app: ReadingApplication
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private lateinit var model: LibraryViewModel

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        store = ViewModelStore()
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[LibraryViewModel::class.java]
        scheduler.runCurrent()
    }

    @After fun cleanUp() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun successfulImportShowsWaitingAndRealProgressThenClearsProgressAndBusy() = runTest(dispatcher) {
        val observed = Collections.synchronizedList(mutableListOf<BookImportProgress?>())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.importProgress.toList(observed) }
        assertNull(model.importProgress.value)
        val gate = holdRepository()
        try {
            model.importBook(Uri.fromFile(sampleFile("library-import.epub")))
            runCurrent()
            assertTrue(model.busy.value)
            assertEquals(ImportStage.WAITING, model.importProgress.value?.stage)
        } finally { gate.unlock() }

        awaitIdle()

        assertNull(model.importProgress.value)
        assertNull(model.error.value)
        assertEquals("山间来信", model.books.value.single().title)
        val progress = synchronized(observed) { observed.filterNotNull() }
        assertTrue(progress.any { it.stage != ImportStage.WAITING })
        assertTrue(progress.all { it.fraction?.let { fraction -> fraction.isFinite() && fraction in 0f..1f } ?: true })
    }

    @Test fun failedImportClearsProgressAndRetryStartsFreshBeforeSuccessfulImport() = runTest(dispatcher) {
        val source = File(app.cacheDir, "library-retry.epub").apply { writeText("broken EPUB") }
        val gate = holdRepository()
        try {
            model.importBook(Uri.fromFile(source))
            runCurrent()
            assertEquals(ImportStage.WAITING, model.importProgress.value?.stage)
        } finally { gate.unlock() }
        awaitIdle()
        assertNull(model.importProgress.value)
        assertNotNull(model.error.value)
        assertTrue(model.books.value.isEmpty())

        copySampleTo(source)
        model.dismissError()
        val retryGate = holdRepository()
        try {
            model.importBook(Uri.fromFile(source))
            runCurrent()
            assertTrue(model.busy.value)
            assertEquals(ImportStage.WAITING, model.importProgress.value?.stage)
        } finally { retryGate.unlock() }
        awaitIdle()

        assertNull(model.importProgress.value)
        assertNull(model.error.value)
        assertEquals(1, model.books.value.size)
    }

    @Test fun removingABookIsBusyWithoutShowingImportProgress() = runTest(dispatcher) {
        val book = app.repository.importBook(Uri.fromFile(sampleFile("library-remove.epub")))
        val observed = Collections.synchronizedList(mutableListOf<BookImportProgress?>())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.importProgress.toList(observed) }
        val gate = holdRepository()
        try {
            model.removeBook(book)
            runCurrent()
            assertTrue(model.busy.value)
            assertNull(model.importProgress.value)
        } finally { gate.unlock() }
        awaitIdle()

        assertNull(model.importProgress.value)
        assertNull(model.error.value)
        assertTrue(model.books.value.isEmpty())
        assertTrue(synchronized(observed) { observed.all { it == null } })
    }

    @Test fun aBlockedPreviousSnackbarCannotKeepTheNextCompletedImportBusy() = runTest(dispatcher) {
        val firstMessage = CompletableDeferred<Unit>()
        val releaseSnackbar = CompletableDeferred<Unit>()
        val messages = Collections.synchronizedList(mutableListOf<String>())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.messages.collect { message ->
                messages += message
                if (messages.size == 1) {
                    firstMessage.complete(Unit)
                    releaseSnackbar.await()
                }
            }
        }
        try {
            val uri = Uri.fromFile(sampleFile("library-blocked-snackbar.epub"))
            model.importBook(uri)
            withContext(Dispatchers.Default) { withTimeout(10_000) { firstMessage.await() } }
            awaitIdle()
            val bookId = model.books.value.single().id

            val secondCompleted = CompletableDeferred<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.importProgress.first { it?.stage == ImportStage.COMPLETED }
                secondCompleted.complete(Unit)
            }
            val gate = holdRepository()
            try {
                // The duplicate URI follows the real repository's fast success path.
                model.importBook(uri)
                runCurrent()
                assertTrue(model.busy.value)
                assertEquals(ImportStage.WAITING, model.importProgress.value?.stage)
            } finally { gate.unlock() }

            withContext(Dispatchers.Default) { withTimeout(10_000) { secondCompleted.await() } }
            app.repository.refresh()
            awaitIdle()

            assertFalse(releaseSnackbar.isCompleted)
            assertEquals(1, messages.size)
            assertNull(model.importProgress.value)
            assertFalse(model.busy.value)
            assertNull(model.error.value)
            assertEquals(bookId, model.books.value.single().id)
            assertEquals(bookId, app.repository.loadSpeechBook(bookId).book.id)
        } finally {
            releaseSnackbar.complete(Unit)
            runCurrent()
        }
    }

    private suspend fun holdRepository(): Mutex {
        val gate = ReflectionHelpers.getField<Mutex>(app.repository, "mutations")
        gate.lock()
        return gate
    }

    private suspend fun awaitIdle() {
        // File parsing uses Dispatchers.IO, so this timeout must use real time rather than virtual time.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { model.busy.first { !it } }
        }
        assertFalse(model.busy.value)
    }

    private fun sampleFile(name: String): File = File(app.cacheDir, name).also(::copySampleTo)
    private fun copySampleTo(target: File) {
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { source ->
            target.outputStream().use { source.copyTo(it) }
        }
    }
}
