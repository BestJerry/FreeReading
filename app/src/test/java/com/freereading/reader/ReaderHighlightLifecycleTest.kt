package com.freereading.reader

import android.net.Uri
import android.os.Looper
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.readium.r2.shared.publication.Publication
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
