@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.reader

import android.net.Uri
import android.graphics.PointF
import android.os.Looper
import android.view.ViewConfiguration
import androidx.compose.runtime.MutableState
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.PlaybackStartFailure
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Actual Activity/Readium view lifecycle and decoration submissions; WebView does not execute JS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReaderHighlightLifecycleTest {
    private lateinit var app: ReadingApplication
    private lateinit var book: SpeechBook
    private lateinit var activity: ReaderActivity
    private lateinit var fragment: EpubNavigatorFragment
    private var controller: ActivityController<ReaderActivity>? = null
    private var opened: Publication? = null

    @Before fun openRealBookAndInstallItsActualNavigator() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        val sample = File(app.cacheDir, "highlight-lifecycle-sample.epub")
        javaClass.getResourceAsStream("/fixtures/reading-sample.epub")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        runBlocking {
            val record = app.repository.importBook(Uri.fromFile(sample))
            book = app.repository.loadSpeechBook(record.id)
            opened = app.repository.readium.open(app.repository.bookFile(record.id))
        }
        PlaybackHub.publish(snapshot(1))
        val lifecycle = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, book.book.id)).create()
        controller = lifecycle
        activity = lifecycle.get()
        // Avoid opening a second Publication: exercise installNavigator with the real file above.
        ReflectionHelpers.getField<Job?>(activity, "loadJob")?.cancel()
        // No visible()/Compose frame pumping: this test checks Fragment lifecycle and cached
        // decoration submissions, not a rendered window or JavaScript pixels.
        lifecycle.start().resume()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "markManualNavigation")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "installNavigator",
            ClassParameter.from(Publication::class.java, requireNotNull(opened)),
            ClassParameter.from(SpeechBook::class.java, book),
            ClassParameter.from(ReadingProgress::class.java, ReadingProgress(book.book.id)))
        fragment = ReflectionHelpers.getField(activity, "navigator")
        await("started Readium view binding") { highlighter() != null }
        assertTrue(fragment.isAdded)
        assertNotNull(fragment.view)
    }

    @After fun closeActualActivityAndBook() {
        controller?.let { lifecycle ->
            if (!lifecycle.get().isDestroyed) lifecycle.pause().stop().destroy()
        }
        opened?.close()
        PlaybackHub.publish(PlaybackSnapshot())
        shadowOf(Looper.getMainLooper()).idle()
        if (::app.isInitialized) File(app.filesDir, "books").deleteRecursively()
    }

    @Test fun openingContentsUsesTheLatestNavigatorPageInsteadOfTheDebouncedOrAudioCursor() {
        val pages = chapterPages()
        val latest = pages[1]
        val stale = pages[0]
        val jumps = recordNavigation()
        setNavigatorPage(latest)
        setDisplayedPage(stale)
        PlaybackHub.publish(snapshot(1))
        shadowOf(app).clearStartedServices()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openContents")

        assertTrue(ReflectionHelpers.callInstanceMethod(activity, "getShowContents"))
        assertEquals(1, selectedContentsIndex())
        assertEquals(3, contentsEntries().size)
        assertEquals(latest.href.removeFragment(), contentsEntries()[1].link.url().removeFragment())
        assertEquals(latest, fragment.currentLocator.value)
        assertTrue(jumps.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(following())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissContents")
    }

    @Test fun reopeningContentsTakesANewNavigatorSnapshotInsteadOfItsPreviousChapter() {
        val pages = chapterPages()
        val jumps = recordNavigation()
        setNavigatorPage(pages[0])
        setDisplayedPage(pages[2])
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openContents")
        assertEquals(0, selectedContentsIndex())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissContents")
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getShowContents"))

        setNavigatorPage(pages[2])
        setDisplayedPage(pages[0])
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openContents")

        assertEquals(2, selectedContentsIndex())
        assertEquals(pages[2].href.removeFragment(), contentsEntries()[2].link.url().removeFragment())
        assertTrue(jumps.isEmpty())
        assertFalse(activity.isFinishing)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissContents")
    }

    @Test fun openingAndDismissingContentsPreservesFollowingWithoutTransportOrNavigation() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial followed audio page") { jumps.isNotEmpty() }
        jumps.clear()
        val latest = chapterPages()[1]
        setNavigatorPage(latest)
        setDisplayedPage(chapterPages()[0])
        val audio = PlaybackHub.state.value
        shadowOf(app).clearStartedServices()
        assertTrue(following())

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openContents")
        assertEquals(1, selectedContentsIndex())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissContents")

        assertTrue(following())
        assertEquals(audio, PlaybackHub.state.value)
        assertEquals(latest, fragment.currentLocator.value)
        assertTrue(jumps.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(activity.isFinishing)
    }

    @Test fun firstPageReadyBindsTheCurrentSentenceAndSameSentenceReloadForcesAFreshDecoration() {
        assertTrue("A not-ready page must not receive a premature cached highlight", decorations().isEmpty())
        val callback = pageListener()
        callback.onPageLoaded()
        await("first sentence decoration") { decorations().singleOrNull()?.locator?.text?.highlight == book.sentences[1].text }
        val first = decorations().single()

        callback.onPageLoaded()

        await("same-sentence reload generation") { decorations().singleOrNull()?.id != first.id }
        val reloaded = decorations().single()
        assertNotEquals(first.id, reloaded.id)
        assertEquals(first.locator, reloaded.locator)
        assertEquals(1, decorations().size)
    }

    @Test fun stoppedViewsDoNotCollectAudioAndRestartBindsOnlyTheNewestSentence() {
        pageListener().onPageLoaded()
        await("initial highlight") { decorations().isNotEmpty() }
        val oldSynchronizer = highlighter()
        val initial = decorations().single()
        requireNotNull(controller).pause().stop()
        await("view-bound worker stop") { highlighter() == null }
        PlaybackHub.publish(snapshot(2))
        PlaybackHub.publish(snapshot(3))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial.id, decorations().single().id)

        requireNotNull(controller).start().resume()

        await("foreground newest highlight") { decorations().singleOrNull()?.locator?.text?.highlight == book.sentences[3].text }
        assertNotSame(oldSynchronizer, highlighter())
        assertNotEquals(initial.id, decorations().single().id)
        assertEquals(1, decorations().size)
        PlaybackHub.publish(PlaybackSnapshot())
        await("stopped audio removes speech group") { decorations().isEmpty() }
    }

    @Test fun destroyingAndRecreatingTheReadiumViewRebindsTheListenerWithoutRecreatingTheFragment() {
        pageListener().onPageLoaded()
        await("initial highlight") { decorations().isNotEmpty() }
        val initial = decorations().single()
        val firstOwner = fragment.viewLifecycleOwner
        val firstSynchronizer = highlighter()
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        await("destroyed-view worker cleanup") { highlighter() == null }
        assertNull(fragment.view)
        PlaybackHub.publish(snapshot(2))
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        await("new view worker binding") { highlighter() != null }
        assertSame(fragment, ReflectionHelpers.getField<EpubNavigatorFragment>(activity, "navigator"))
        assertNotSame(firstOwner, fragment.viewLifecycleOwner)
        assertNotSame(firstSynchronizer, highlighter())
        assertEquals("Recreated view must await its own page-ready event", initial.id, decorations().single().id)

        pageListener().onPageLoaded()

        await("recreated-view current sentence") { decorations().singleOrNull()?.locator?.text?.highlight == book.sentences[2].text }
        assertNotEquals(initial.id, decorations().single().id)
        assertEquals(1, decorations().size)
    }

    @Test fun continuousSpeechNavigatesToTheCurrentSentenceWithinAndAcrossResources() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial audio location") { jumps.lastOrNull() == locator(1) }
        val nextChapter = book.sentences.first { it.chapterIndex != book.sentences[1].chapterIndex }.index

        for (index in listOf(2, 3, nextChapter)) {
            PlaybackHub.publish(snapshot(index).copy(isPlaying = true))
            await("automatic navigation to sentence $index") { jumps.lastOrNull() == locator(index) }
        }

        assertEquals(book.sentences[nextChapter].source!!.locator.href, jumps.last().href)
        assertTrue(following())
    }

    @Test fun leftCenterAndRightTapsOnlyToggleControlsAndKeepAudioFollowing() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial followed page") { jumps.isNotEmpty() }
        jumps.clear()
        requireNotNull(fragment.view).layout(0, 0, 1_000, 1_200)
        val page = fragment.currentLocator.value
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")
        val listener = readingInputListener()

        for (x in listOf(20f, 500f, 980f)) {
            assertTrue(listener.onTap(TapEvent(PointF(x, 600f))))
            assertTrue(ReflectionHelpers.callInstanceMethod(activity, "getShowReadingControls"))
            assertTrue(listener.onTap(TapEvent(PointF(x, 600f))))
            assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getShowReadingControls"))
            assertEquals(page, fragment.currentLocator.value)
            assertEquals(revision, ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision"))
            assertEquals(ReadingPlaybackIntent.CONTINUE_AUDIO,
                ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(activity, "getReadingPlaybackIntent"))
            assertTrue(following())
            assertTrue(jumps.isEmpty())
        }

        PlaybackHub.publish(snapshot(2).copy(isPlaying = true))
        await("narration still follows after menu taps") { jumps.lastOrNull() == locator(2) }
    }

    @Test fun menuTapsDoNotCancelAPendingReadingLocationLookup() {
        val pending = Job()
        val requested = locator(1)
        ReflectionHelpers.setField(activity, "readingStartJob", pending)
        ReflectionHelpers.setField(activity, "requestedReadingStart", requested)
        val revision = ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision")
        val listener = readingInputListener()
        requireNotNull(fragment.view).layout(0, 0, 1_000, 1_200)
        try {
            for (x in listOf(20f, 500f, 980f)) {
                assertTrue(listener.onTap(TapEvent(PointF(x, 600f))))
                assertTrue(pending.isActive)
                assertSame(pending, ReflectionHelpers.getField<Job>(activity, "readingStartJob"))
                assertSame(requested, ReflectionHelpers.getField<Locator>(activity, "requestedReadingStart"))
                assertEquals(revision, ReflectionHelpers.getField<Long>(activity, "readingNavigationRevision"))
                assertEquals(ReadingPlaybackIntent.FROM_READING_LOCATION,
                    ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(activity, "getReadingPlaybackIntent"))
            }
        } finally { pending.cancel() }
    }

    @Test fun tinyTouchMovementKeepsAutomaticPagingAndRealBrowsingCanResumeFromTheFooterAction() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial audio location") { jumps.isNotEmpty() }
        val listener = readingInputListener()
        val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        fun drag(type: DragEvent.Type, offset: Float) = listener.onDrag(
            DragEvent(type, PointF(20f, 20f), PointF(offset, 0f)))

        drag(DragEvent.Type.Start, slop / 2)
        drag(DragEvent.Type.Move, slop / 2)
        drag(DragEvent.Type.End, slop / 2)
        assertTrue(following())
        PlaybackHub.publish(snapshot(2).copy(isPlaying = true))
        await("next sentence after a slight touch") { jumps.lastOrNull() == locator(2) }

        drag(DragEvent.Type.Start, slop + 1)
        drag(DragEvent.Type.Move, slop + 1)
        drag(DragEvent.Type.End, slop + 1)
        assertFalse(following())
        val pausedJumps = jumps.size
        PlaybackHub.publish(snapshot(3).copy(isPlaying = true))
        await("highlight while browsing independently") { decorations().singleOrNull()?.locator == locator(3) }
        assertEquals(pausedJumps, jumps.size)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleAudioFollow")

        await("resume following at the current sentence") { jumps.lastOrNull() == locator(3) }
        assertTrue(following())
    }

    @Test fun startingTheSameSentenceAfterBrowsingCatchesUpWhenPlaybackStarts() {
        val jumps = recordNavigation()
        pageListener().onPageLoaded()
        await("initial detached highlight") { decorations().isNotEmpty() }
        assertFalse(following())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
            ClassParameter.from(SpeechSentence::class.java, book.sentences[1]))
        assertTrue(following())

        PlaybackHub.publish(snapshot(1).copy(isPlaying = true, playbackSessionId = pendingRequestId()))

        await("same-index playback start navigation") { jumps.lastOrNull() == locator(1) }
    }

    @Test fun explicitlyDisablingAutomaticPagingSurvivesANewStartAndForegroundRebinding() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial audio location") { jumps.isNotEmpty() }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleAudioFollow")
        assertFalse(following())
        val pausedJumps = jumps.size
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
            ClassParameter.from(SpeechSentence::class.java, book.sentences[2]))
        val acceptedRequest = pendingRequestId()
        PlaybackHub.publish(snapshot(2).copy(isPlaying = true, playbackSessionId = acceptedRequest))
        await("highlight with automatic paging disabled") { decorations().singleOrNull()?.locator == locator(2) }
        assertFalse(following())
        requireNotNull(controller).pause().stop()
        await("background worker stop") { highlighter() == null }
        requireNotNull(controller).start().resume()
        await("foreground worker restart") { highlighter() != null }
        PlaybackHub.publish(snapshot(3).copy(isPlaying = true, playbackSessionId = acceptedRequest))
        await("foreground disabled highlight") { decorations().singleOrNull()?.locator == locator(3) }
        assertEquals(pausedJumps, jumps.size)
        assertFalse(following())
    }

    @Test fun aPendingStartBlocksAnEarlierNavigationUntilTheServiceConfirmsTheNewSentence() {
        val jumps = recordNavigation()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("initial audio location") { jumps.isNotEmpty() }
        jumps.clear()
        val synchronizer = requireNotNull(highlighter())
        val apply: suspend (List<Decoration>) -> Unit = ReflectionHelpers.getField(synchronizer, "apply")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finished = false
        val delayed: suspend (List<Decoration>) -> Unit = { decorations ->
            apply(decorations)
            if (decorations.singleOrNull()?.locator == locator(2)) {
                entered.complete(Unit)
                release.await()
                finished = true
            }
        }
        ReflectionHelpers.setField(synchronizer, "apply", delayed)
        PlaybackHub.publish(snapshot(2).copy(isPlaying = true))
        await("old sentence draw suspended before navigation") { entered.isCompleted }
        val requested = book.sentences.first { it.chapterIndex != book.sentences[2].chapterIndex }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "markManualNavigation")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
                ClassParameter.from(SpeechSentence::class.java, requested))
            val requestId = pendingRequestId()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleListening")
            assertEquals(requestId, pendingRequestId())
            release.complete(Unit)
            await("old sentence apply completion") { finished }

            assertTrue("Old Hub state cannot pull the manually selected chapter back", jumps.isEmpty())
            PlaybackHub.publish(snapshot(requested.index).copy(isPlaying = true, playbackSessionId = requestId))
            await("confirmed new start navigation") { jumps.lastOrNull() == locator(requested.index) }
            assertEquals(listOf(locator(requested.index)), jumps)
        } finally { release.complete(Unit) }
    }

    @Test fun returningAfterTheRequestedSentenceHasAlreadyFinishedFollowsTheAcceptedSession() {
        val jumps = recordNavigation()
        PlaybackHub.publish(snapshot(3).copy(isPlaying = true, playbackSessionId = "previous-session"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "followCurrentAudio")
        pageListener().onPageLoaded()
        await("previous session page") { jumps.lastOrNull() == locator(3) }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "markManualNavigation")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
            ClassParameter.from(SpeechSentence::class.java, book.sentences[1]))
        val requestId = pendingRequestId()
        requireNotNull(controller).pause().stop()
        await("hidden reader has stopped collecting") { highlighter() == null }
        PlaybackHub.publish(snapshot(1).copy(isPlaying = true, playbackSessionId = requestId))
        PlaybackHub.publish(snapshot(2).copy(isPlaying = true, playbackSessionId = requestId))

        requireNotNull(controller).start().resume()

        await("new session current sentence after missing the initial acknowledgement") {
            jumps.lastOrNull() == locator(2)
        }
        assertNull(ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence"))
        assertTrue(following())
    }

    @Test fun anEarlierStartCannotConfirmTheLatestOfTwoQueuedRequests() {
        val jumps = recordNavigation()
        pageListener().onPageLoaded()
        await("initial detached decoration") { decorations().isNotEmpty() }
        val earlier = book.sentences[2]
        val latest = book.sentences.first { it.chapterIndex != earlier.chapterIndex }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
            ClassParameter.from(SpeechSentence::class.java, earlier))
        val earlierId = pendingRequestId()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startSentence",
            ClassParameter.from(SpeechSentence::class.java, latest))
        val latestId = pendingRequestId()
        val synchronizer = requireNotNull(highlighter())
        val apply: suspend (List<Decoration>) -> Unit = ReflectionHelpers.getField(synchronizer, "apply")
        var drewEarlier = false
        val recordDraw: suspend (List<Decoration>) -> Unit = { drawn ->
            apply(drawn)
            if (drawn.singleOrNull()?.locator == locator(earlier.index)) drewEarlier = true
        }
        ReflectionHelpers.setField(synchronizer, "apply", recordDraw)

        PlaybackHub.publish(snapshot(earlier.index).copy(isPlaying = true, playbackSessionId = earlierId))
        await("earlier request decoration completed") { drewEarlier }

        assertTrue("The earlier start must not pull the selected page back", jumps.isEmpty())
        assertEquals(latestId, pendingRequestId())
        PlaybackHub.publish(snapshot(latest.index).copy(isPlaying = true, playbackSessionId = latestId))
        await("latest request navigation") { jumps.lastOrNull() == locator(latest.index) }
        assertEquals(listOf(locator(latest.index)), jumps)
    }

    @Test fun anAsynchronousStartFailureWithoutAnyCursorChangeRestoresTheRetryPosition() {
        val jumps = recordNavigation()
        pageListener().onPageLoaded()
        await("initial detached decoration") { decorations().isNotEmpty() }
        val requested = book.sentences.first { it.chapterIndex != book.sentences[1].chapterIndex }
        val desired = ReadingProgress(book.book.id, requested.locatorJson, requested.index, 4_321)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestAudioStart",
            ClassParameter.from(ReadingProgress::class.java, desired))
        val requestId = pendingRequestId()

        PlaybackHub.publish(snapshot(1).copy(error = "前台启动被拒绝",
            startFailure = PlaybackStartFailure(requestId, book.book.id, requested.index, 4_321)))

        await("failure acknowledgement with an unchanged audio cursor") {
            ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence") == null
        }
        assertFalse(following())
        val retry: ReadingProgress = ReflectionHelpers.getField(activity, "resumableProgress")
        assertEquals(requested.index, retry.sentenceIndex)
        assertEquals(4_321L, retry.audioOffsetMs)
        assertTrue(jumps.isEmpty())
    }

    @Test fun aHiddenReaderRecoversARejectedRequestAfterTheServiceHasStopped() {
        val jumps = recordNavigation()
        pageListener().onPageLoaded()
        await("initial detached decoration") { decorations().isNotEmpty() }
        val requested = book.sentences.first { it.chapterIndex != book.sentences[1].chapterIndex }
        val desired = ReadingProgress(book.book.id, requested.locatorJson, requested.index, 4_321)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestAudioStart",
            ClassParameter.from(ReadingProgress::class.java, desired))
        val failure = PlaybackStartFailure(pendingRequestId(), book.book.id, requested.index, 4_321)
        requireNotNull(controller).pause().stop()
        await("hidden view binding stopped") { highlighter() == null }
        PlaybackHub.publish(PlaybackSnapshot(startFailure = failure))

        requireNotNull(controller).start().resume()

        await("rejected request restored after service teardown") {
            ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence") == null
        }
        assertFalse(following())
        val retry: ReadingProgress = ReflectionHelpers.getField(activity, "pendingStoppedProgress")
        assertEquals(requested.index, retry.sentenceIndex)
        assertEquals(4_321L, retry.audioOffsetMs)
        assertTrue(jumps.isEmpty())
    }

    private fun locator(index: Int): Locator = requireNotNull(Locator.fromJSON(JSONObject(book.sentences[index].locatorJson)))
    private fun chapterPages(): List<Locator> = book.sentences.distinctBy { it.chapterIndex }
        .map { locator(it.index) }.also { assertEquals(3, it.size) }

    private fun setNavigatorPage(page: Locator) {
        // Readium 3.1.2 exposes this exact MutableStateFlow through currentLocator.
        val current: MutableStateFlow<Locator> = ReflectionHelpers.getField(fragment, "_currentLocator")
        current.value = page
    }

    private fun setDisplayedPage(page: Locator) {
        val displayed: MutableState<Locator?> = ReflectionHelpers.getField(activity, "visibleLocator\$delegate")
        displayed.value = page
    }

    private fun selectedContentsIndex(): Int? =
        ReflectionHelpers.callInstanceMethod(activity, "getSelectedContentsIndex")

    private fun contentsEntries(): List<ContentsEntry> =
        ReflectionHelpers.callInstanceMethod(activity, "getContentsEntries")

    private fun following(): Boolean = ReflectionHelpers.callInstanceMethod(activity, "getFollowingAudio")
    private fun pendingRequestId(): String = requireNotNull(ReflectionHelpers.getField<String?>(activity, "pendingAudioRequestId"))

    private fun recordNavigation(): MutableList<Locator> {
        val jumps = mutableListOf<Locator>()
        val previous: EpubNavigatorFragment.Listener = ReflectionHelpers.getField(fragment, "listener")
        ReflectionHelpers.setField(fragment, "listener", object : EpubNavigatorFragment.Listener by previous {
            override fun onJumpToLocator(locator: Locator) { jumps += locator }
        })
        return jumps
    }

    private fun readingInputListener(): InputListener {
        val composite: Any = ReflectionHelpers.getField(fragment, "inputListener")
        val listeners: List<InputListener> = ReflectionHelpers.getField(composite, "listeners")
        return listeners.first { it.javaClass.name.startsWith(ReaderActivity::class.java.name + "$") }
    }

    private fun snapshot(index: Int) = PlaybackSnapshot(bookId = book.book.id, sentenceIndex = index,
        sentenceCount = book.sentences.size, text = book.sentences[index].text, isPlaying = false)

    private fun highlighter(): AudioHighlightSynchronizer? = ReflectionHelpers.getField(activity, "audioHighlighter")

    private fun pageListener(): EpubNavigatorFragment.PaginationListener =
        ReflectionHelpers.getField(fragment, "paginationListener")

    private fun decorations(): List<Decoration> {
        val model: Any = ReflectionHelpers.callInstanceMethod(fragment, "getViewModel")
        val groups: Map<String, List<Decoration>> = ReflectionHelpers.getField(model, "decorations")
        return groups["speech"].orEmpty()
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
