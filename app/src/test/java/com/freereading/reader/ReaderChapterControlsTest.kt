package com.freereading.reader

import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.ReadingAudioService
import com.freereading.data.ReadingProgress
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Chapter transport supersedes an older lookup, then follows the service's confirmed new target. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReaderChapterControlsTest {
    private lateinit var app: ReadingApplication
    private lateinit var controller: ActivityController<ReaderActivity>
    private lateinit var activity: ReaderActivity
    private var wasAlive = false
    private val bookId = "chapter-controls-book"

    @Before fun prepareReaderWhileItsDocumentIsStillOpening() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        controller = Robolectric.buildActivity(ReaderActivity::class.java, ReaderActivity.intent(app, bookId)).create()
        activity = controller.get()
        ReflectionHelpers.getField<Job?>(activity, "loadJob")?.cancel()
        wasAlive = PlaybackHub.serviceAlive
        PlaybackHub.serviceAlive = true
        PlaybackHub.publish(PlaybackSnapshot(bookId = bookId, canGoToPreviousChapter = true,
            canGoToNextChapter = true))
        shadowOf(app).clearStartedServices()
    }

    @After fun releaseReader() {
        controller.destroy()
        PlaybackHub.publish(PlaybackSnapshot())
        PlaybackHub.serviceAlive = wasAlive
    }

    @Test fun chapterSelectionCancelsAnOlderLookupAndDiscardsItsResumeCursor() {
        val oldCursor = ReadingProgress(bookId, sentenceIndex = 5, audioOffsetMs = 4_000)
        // The old audio cursor must not steal the page before the new chapter is published.
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleAudioFollow")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "markManualNavigation")
        val pending = Job()
        ReflectionHelpers.setField(activity, "readingStartJob", pending)
        ReflectionHelpers.setField(activity, "resumableProgress", oldCursor)
        ReflectionHelpers.setField(activity, "pendingStoppedProgress", oldCursor)
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getFollowAudio"))

        skip(forward = true)

        assertTrue(pending.isCancelled)
        assertNull(ReflectionHelpers.getField<ReadingProgress?>(activity, "resumableProgress"))
        assertNull(ReflectionHelpers.getField<ReadingProgress?>(activity, "pendingStoppedProgress"))
        assertEquals(ReadingPlaybackIntent.FROM_READING_LOCATION,
            ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(activity, "getReadingPlaybackIntent"))
        assertFalse(ReflectionHelpers.callInstanceMethod(activity, "getFollowAudio"))
        val command = shadowOf(app).nextStartedService
        assertEquals(ReadingAudioService.ACTION_NEXT_CHAPTER, command?.action)
        assertEquals(bookId, command?.getStringExtra(ReadingAudioService.EXTRA_BOOK_ID))
        assertTrue(PlaybackHub.isAuthorizedCommand(requireNotNull(command)))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(activity.isFinishing)

        PlaybackHub.publish(PlaybackHub.state.value.copy(sentenceIndex = 7, sentenceCount = 10,
            followRequestId = command.getStringExtra(ReadingAudioService.EXTRA_FOLLOW_REQUEST_ID)))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "synchronizeAudio")
        assertTrue(ReflectionHelpers.callInstanceMethod(activity, "getFollowingAudio"))
    }

    @Test fun previousChapterWorksWhileTheDocumentIsLoadingAndPreservesStartAcknowledgement() {
        val requestId = PlaybackHub.newPlaybackSessionId()
        ReflectionHelpers.setField(activity, "pendingAudioSentence", 5)
        ReflectionHelpers.setField(activity, "pendingAudioRequestId", requestId)

        skip(forward = false)

        val command = shadowOf(app).nextStartedService
        assertEquals(ReadingAudioService.ACTION_PREVIOUS_CHAPTER, command?.action)
        assertEquals(bookId, command?.getStringExtra(ReadingAudioService.EXTRA_BOOK_ID))
        assertEquals(5, ReflectionHelpers.getField<Int?>(activity, "pendingAudioSentence"))
        assertEquals(requestId, ReflectionHelpers.getField<String?>(activity, "pendingAudioRequestId"))
        assertTrue(ReflectionHelpers.callInstanceMethod(activity, "getFollowAudio"))
    }

    @Test fun missingBoundariesAndAnotherBooksSessionDoNotSendTransportCommands() {
        val pending = Job()
        ReflectionHelpers.setField(activity, "readingStartJob", pending)
        try {
            PlaybackHub.publish(PlaybackSnapshot(bookId = bookId))
            skip(forward = true)
            skip(forward = false)
            PlaybackHub.publish(PlaybackSnapshot(bookId = "other-book", canGoToPreviousChapter = true,
                canGoToNextChapter = true))
            skip(forward = true)
            skip(forward = false)

            assertNull(shadowOf(app).nextStartedService)
            assertFalse(pending.isCancelled)
        } finally { pending.cancel() }
    }

    private fun skip(forward: Boolean) = ReflectionHelpers.callInstanceMethod<Unit>(activity,
        "skipListeningChapter", ClassParameter.from(Boolean::class.javaPrimitiveType, forward))
}
