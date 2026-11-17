@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.reader

import android.net.Uri
import android.os.Looper
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.ReadingAudioService
import com.freereading.data.BookRecord
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Pipeline ordering and real Fragment state, without claiming WebView pixel timings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReaderColdOpenLifecycleTest {
    private lateinit var app: ReadingApplication
    private lateinit var book: SpeechBook
    private lateinit var activity: ReaderActivity
    private lateinit var fragment: EpubNavigatorFragment
    private var controller: ActivityController<ReaderActivity>? = null
    private var opened: Publication? = null
    private lateinit var initial: Locator

    @Before fun installTheRealReaderWithOnlyMetadataAndAVisualCursor() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        val sample = File(app.cacheDir, "cold-open-reader.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        runBlocking {
            val record = app.repository.importBook(Uri.fromFile(sample))
            book = app.repository.loadSpeechBook(record.id)
            opened = app.repository.readium.openForReading(app.repository.bookFile(record.id))
        }
        initial = locator(book.sentences.first { it.chapterIndex == 1 }.index)
        PlaybackHub.publish(PlaybackSnapshot())
        val lifecycle = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, book.book.id)).create()
        controller = lifecycle
        activity = lifecycle.get()
        ReflectionHelpers.getField<Job?>(activity, "loadJob")?.cancel()
        lifecycle.start().resume()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "installReadingNavigator",
            ClassParameter.from(Publication::class.java, requireNotNull(opened)),
            ClassParameter.from(BookRecord::class.java, book.book),
            ClassParameter.from(ReadingProgress::class.java, ReadingProgress(book.book.id,
                initial.toJSON().toString(), book.sentences.first { it.chapterIndex == 1 }.index)),
            ClassParameter.from(SpeechBook::class.java, null))
        fragment = ReflectionHelpers.getField(activity, "navigator")
        await("metadata reader binding") { highlighter() != null }
    }

    @After fun closeReaderAndItsPrivateFixture() {
        controller?.let { lifecycle ->
            if (!lifecycle.get().isDestroyed) {
                // A separate test exercises final saves; cleanup must not enqueue another one.
                ReflectionHelpers.setField(activity, "resumableProgress", ReadingProgress(book.book.id))
                lifecycle.pause().stop().destroy()
            }
        }
        opened?.close()
        PlaybackHub.publish(PlaybackSnapshot())
        shadowOf(Looper.getMainLooper()).idle()
        if (::app.isInitialized) File(app.filesDir, "books").deleteRecursively()
    }

    @Test fun theSavedReadingPageIsInstalledBeforeTheListeningIndexEvenStarts() {
        assertTrue(fragment.isAdded)
        assertNotNull(fragment.view)
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getLoading"))
        assertEquals(book.book, ReflectionHelpers.callInstanceMethod<BookRecord>(activity, "getReadingRecord"))
        assertNull(speechBook())
        assertEquals(initial, fragment.currentLocator.value)
        val pending = speechLoad()
        assertFalse(pending.isActive)
        assertFalse(pending.isCompleted)
    }

    @Test fun pageReadinessStartsTheOptionalIndexWithoutReplacingTheFragment() {
        val original = fragment
        pageListener().onPageLoaded()
        await("optional listening index") { speechBook() != null }

        assertSame(original, ReflectionHelpers.getField<EpubNavigatorFragment>(activity, "navigator"))
        assertEquals(initial, fragment.currentLocator.value)
        assertFalse(activity.isFinishing)
    }

    @Test fun aLateIndexBindsTheNewestAudioSentenceWithoutReinstallingTheReader() {
        val jumps = recordNavigation()
        ReflectionHelpers.setField(activity, "audioPageReady", true)
        requireNotNull(highlighter()).onPageLoaded()
        PlaybackHub.publish(snapshot(2))
        shadowOf(Looper.getMainLooper()).idle()

        attach(book)
        await("late index highlight") { decorations().singleOrNull()?.locator == locator(2) }

        assertSame(fragment, ReflectionHelpers.getField<EpubNavigatorFragment>(activity, "navigator"))
        assertEquals(locator(2), jumps.lastOrNull())
    }

    @Test fun aLateIndexCannotPullBackAManuallySelectedChapter() {
        val jumps = recordNavigation()
        val manualPage = locator(book.sentences.first { it.chapterIndex == 2 }.index)
        markManual()
        setPage(manualPage)
        ReflectionHelpers.setField(activity, "audioPageReady", true)
        requireNotNull(highlighter()).onPageLoaded()
        PlaybackHub.publish(snapshot(2))
        shadowOf(Looper.getMainLooper()).idle()

        attach(book)
        await("detached late index highlight") { decorations().singleOrNull()?.locator == locator(2) }

        assertEquals(manualPage, fragment.currentLocator.value)
        assertTrue(jumps.isEmpty())
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getFollowingAudio"))
    }

    @Test fun anEarlyListeningRequestWaitsAndManualNavigationCancelsTheOldTarget() {
        val gate = installSpeechGate()
        val requested = requireNotNull(opened).locatorFromLink(requireNotNull(opened).readingOrder.first())!!
        shadowOf(app).clearStartedServices()
        playFrom(requested)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(gate, speechLoad())
        assertTrue(ReflectionHelpers.getField<Job>(activity, "readingStartJob").isActive)
        assertNull(shadowOf(app).nextStartedService)
        markManual()
        attach(book)
        gate.complete(book)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedService)
        assertNull(ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence"))
    }

    @Test fun anEarlyListeningRequestUsesTheCompletedIndexAndStartsOnlyOnce() {
        val gate = installSpeechGate()
        val requested = requireNotNull(opened).locatorFromLink(requireNotNull(opened).readingOrder.first())!!
        shadowOf(app).clearStartedServices()
        playFrom(requested)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)

        attach(book)
        gate.complete(book)
        await("listening command") { ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence") == 0 }

        val command = shadowOf(app).nextStartedService
        assertEquals(ReadingAudioService.ACTION_PLAY_BOOK, command?.action)
        assertEquals(0, command?.getIntExtra(ReadingAudioService.EXTRA_INDEX, -1))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun aBrokenOptionalIndexKeepsTheDocumentOpenAndListeningCanRetry() {
        val file = File(app.repository.bookFile(book.book.id).parentFile, "speech.json")
        val original = file.readBytes()
        file.writeText("{broken listening index")
        val failed = speechLoad()
        pageListener().onPageLoaded()
        await("failed optional index") { failed.isCompleted }

        assertTrue(failed.isCancelled)
        assertNull(speechBook())
        assertNull(ReflectionHelpers.callInstanceMethod<String?>(activity, "getOpeningError"))
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getLoading"))
        assertTrue(fragment.isAdded)
        pageListener().onPageLoaded()
        assertSame(failed, speechLoad())

        file.writeBytes(original)
        file.setLastModified(System.currentTimeMillis() + 1_000)
        val requested = requireNotNull(opened).locatorFromLink(requireNotNull(opened).readingOrder.first())!!
        playFrom(requested)
        await("retry optional index") { speechBook() != null }
        assertFalse(speechLoad().isCancelled)
        assertTrue(fragment.isAdded)
    }

    @Test fun anIndexFromAReimportedBookCannotReplaceThisReadersMetadata() {
        val otherGeneration = book.copy(book = book.book.copy(addedAt = book.book.addedAt + 1))
        attach(otherGeneration)

        assertNull(speechBook())
        assertEquals(book.book, ReflectionHelpers.callInstanceMethod<BookRecord>(activity, "getReadingRecord"))
        assertSame(fragment, ReflectionHelpers.getField<EpubNavigatorFragment>(activity, "navigator"))
    }

    @Test fun deferredSavingMapsTheCapturedTextAnchorAfterTheActivityHasBeenDestroyed() {
        val target = book.sentences.first { it.chapterIndex == 2 }
        val page = locator(target.index)
        val capturedAt = System.currentTimeMillis()
        markManual()
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")
        requireNotNull(controller).pause().stop().destroy()
        controller = null

        runBlocking { saveDeferred(page, locator(target.index), 2, revision, capturedAt) }

        val saved = app.repository.progress(book.book.id)
        assertEquals(target.index, saved.sentenceIndex)
        assertEquals(page, Locator.fromJSON(JSONObject(requireNotNull(saved.locatorJson))))
        assertEquals(capturedAt, saved.updatedAt)
    }

    @Test fun deferredSavingRetainsSourceElementFallbackAfterThePublicationWasClosed() {
        val expected = book.sentences.first { it.chapterIndex == 0 && it.text.startsWith("阅读并不") }
        val page = locator(expected.index)
        val raw = page.copy(locations = Locator.Locations(otherLocations = mapOf(
            "cssSelector" to "body > p:nth-of-type(2) > em")), text = Locator.Text())
        markManual()
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")
        requireNotNull(controller).pause().stop().destroy()
        controller = null

        runBlocking { saveDeferred(page, raw, 0, revision, System.currentTimeMillis()) }

        assertEquals(expected.index, app.repository.progress(book.book.id).sentenceIndex)
    }

    @Test fun aSlowFinalMappingCannotOverwriteANewerReadingVisit() {
        val target = book.sentences.first { it.chapterIndex == 2 }
        val oldCapture = System.currentTimeMillis()
        val newer = ReadingProgress(book.book.id, locator(1).toJSON().toString(), 1,
            updatedAt = oldCapture + 10_000)
        app.repository.saveProgress(newer)
        markManual()
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")

        runBlocking { saveDeferred(locator(target.index), locator(target.index), 2, revision, oldCapture) }

        assertEquals(newer, app.repository.progress(book.book.id))
    }

    @Test fun aFullReadyFinalSaveCannotWriteIntoAReimportedBookGeneration() {
        attach(book)
        markManual()
        val target = book.sentences.first { it.chapterIndex == 2 }
        val page = locator(target.index)
        setPage(page)
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")
        val snapshotType = Class.forName(ReaderActivity::class.java.name + "\$VisibleTextSnapshot")
        val constructor = snapshotType.getDeclaredConstructor(Locator::class.java, Locator::class.java,
            Long::class.javaPrimitiveType).apply { isAccessible = true }
        ReflectionHelpers.setField(activity, "visibleTextSnapshot", constructor.newInstance(page, page, revision))
        val progressLock: Any = ReflectionHelpers.getField(app.repository, "progressLock")
        lateinit var saveJob: Job
        synchronized(progressLock) {
            // Hold the actual commit lock so the save captures the old generation before the
            // metadata/progress commit below, regardless of whether the WebView query suspends.
            requireNotNull(controller).pause()
            val pending: Map<String, Set<Any>> = ReflectionHelpers.getField(app.repository, "readingSaves")
            val ticket = requireNotNull(pending[book.book.id]).single()
            saveJob = ReflectionHelpers.getField(ticket, "job")
            assertFalse(saveJob.isCompleted)
            val directory = requireNotNull(app.repository.bookFile(book.book.id).parentFile)
            val metadata = File(directory, "book.json")
            metadata.writeText(JSONObject(metadata.readText()).put("addedAt", book.book.addedAt + 1).toString())
            File(directory, "progress.json").delete()
            val cache: MutableMap<String, ReadingProgress> = ReflectionHelpers.getField(app.repository, "progressCache")
            cache.remove(book.book.id)
        }
        requireNotNull(controller).stop().destroy()
        controller = null
        await("guarded full-index final save") { saveJob.isCompleted }

        val fresh = app.repository.progress(book.book.id)
        assertNull(fresh.locatorJson)
        assertEquals(0, fresh.sentenceIndex)
        assertEquals(0L, fresh.updatedAt)
    }

    private fun installSpeechGate(): CompletableDeferred<SpeechBook> {
        speechLoad().cancel()
        return CompletableDeferred<SpeechBook>().also { ReflectionHelpers.setField(activity, "speechLoad", it) }
    }

    private fun playFrom(locator: Locator) = ReflectionHelpers.callInstanceMethod<Unit>(activity,
        "playFromReadingLocation", ClassParameter.from(Locator::class.java, locator))

    private fun attach(loaded: SpeechBook) = ReflectionHelpers.callInstanceMethod<Unit>(activity,
        "attachSpeechBook", ClassParameter.from(SpeechBook::class.java, loaded))

    private fun markManual() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "markManualNavigation")
    private fun speechLoad(): Deferred<SpeechBook> = ReflectionHelpers.getField(activity, "speechLoad")
    private fun speechBook(): SpeechBook? = ReflectionHelpers.callInstanceMethod(activity, "getSpeechBook")
    private fun locator(index: Int): Locator = requireNotNull(Locator.fromJSON(JSONObject(book.sentences[index].locatorJson)))
    private fun snapshot(index: Int) = PlaybackSnapshot(bookId = book.book.id, sentenceIndex = index,
        sentenceCount = book.sentences.size, text = book.sentences[index].text, isPlaying = false)
    private fun highlighter(): AudioHighlightSynchronizer? = ReflectionHelpers.getField(activity, "audioHighlighter")
    private fun pageListener(): EpubNavigatorFragment.PaginationListener = ReflectionHelpers.getField(fragment, "paginationListener")

    private fun decorations(): List<Decoration> {
        val model: Any = ReflectionHelpers.callInstanceMethod(fragment, "getViewModel")
        val groups: Map<String, List<Decoration>> = ReflectionHelpers.getField(model, "decorations")
        return groups["speech"].orEmpty()
    }

    private fun setPage(page: Locator) {
        val flow: kotlinx.coroutines.flow.MutableStateFlow<Locator> = ReflectionHelpers.getField(fragment, "_currentLocator")
        flow.value = page
    }

    private fun recordNavigation(): MutableList<Locator> {
        val jumps = mutableListOf<Locator>()
        val previous: EpubNavigatorFragment.Listener = ReflectionHelpers.getField(fragment, "listener")
        ReflectionHelpers.setField(fragment, "listener", object : EpubNavigatorFragment.Listener by previous {
            override fun onJumpToLocator(locator: Locator) { jumps += locator }
        })
        return jumps
    }

    private suspend fun saveDeferred(page: Locator, raw: Locator, chapter: Int, revision: Long, capturedAt: Long) {
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val method = ReaderActivity::class.java.getDeclaredMethod("saveDeferredReadingLocation",
                Locator::class.java, Locator::class.java, Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Continuation::class.java)
            method.isAccessible = true
            method.invoke(activity, page, raw, chapter, revision, capturedAt, book.book.addedAt, continuation)
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for $description")
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
