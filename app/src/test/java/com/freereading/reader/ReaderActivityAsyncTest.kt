package com.freereading.reader

import android.os.Bundle
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.ReadingAudioService
import com.freereading.data.ReadingProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReaderActivityAsyncTest {
    @Test fun stoppingCancelsAnUnfinishedReadingLocationBeforeItCanRestartPlayback() {
        assertPendingLocationCancelled("stopListening", "reader-async-probe")
    }

    @Test fun stoppingAlsoCancelsPendingReadingStartAfterAudioHasMovedToAnotherBook() {
        assertPendingLocationCancelled("stopListening", "another-book")
    }

    @Test fun primaryPlaybackActionSupersedesAnUnfinishedReadingStart() {
        assertPendingLocationCancelled("toggleListening", "reader-async-probe")
    }

    @Test fun pausedAudioCanResumeWhileAManuallyChosenReadingLocationIsStillLoading() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val saved = Bundle().apply {
            putString("reader_playback_intent", ReadingPlaybackIntent.FROM_READING_LOCATION.name)
        }
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create(saved)
        val wasAlive = PlaybackHub.serviceAlive
        try {
            PlaybackHub.publish(PlaybackSnapshot(bookId = "reader-async-probe", positionMs = 6_000))
            PlaybackHub.serviceAlive = true
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleListening")

            assertEquals(ReadingAudioService.ACTION_TOGGLE, shadowOf(app).nextStartedService?.action)
        } finally {
            PlaybackHub.serviceAlive = wasAlive
            PlaybackHub.publish(PlaybackSnapshot())
            controller.destroy()
        }
    }

    @Test fun unavailableReadingLocationDoesNotDiscardTheStoppedAudioCursor() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create()
        val stopped = ReadingProgress("reader-async-probe", null, 7, 6_000)
        try {
            ReflectionHelpers.setField(controller.get(), "pendingStoppedProgress", stopped)
            ReflectionHelpers.setField(controller.get(), "resumableProgress", stopped)

            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "playFromVisibleParagraph")

            assertEquals(stopped, ReflectionHelpers.getField<ReadingProgress>(controller.get(), "pendingStoppedProgress"))
            assertEquals(stopped, ReflectionHelpers.getField<ReadingProgress>(controller.get(), "resumableProgress"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun stoppingBeforeTheDocumentLoadsRetainsTheCursorAcrossRecreation() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        try {
            PlaybackHub.publish(PlaybackSnapshot(bookId = "reader-async-probe", sentenceIndex = 7, positionMs = 6_000))
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "stopListening")
            val stopped = ReflectionHelpers.getField<ReadingProgress>(controller.get(), "pendingStoppedProgress")
            assertEquals(7, stopped.sentenceIndex)
            assertEquals(6_000L, stopped.audioOffsetMs)
            assertNull("A stop must survive even without a loaded document locator", stopped.locatorJson)
            controller.saveInstanceState(saved)
        } finally {
            controller.destroy()
            PlaybackHub.publish(PlaybackSnapshot())
        }

        val recreated = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            val resumed = ReflectionHelpers.getField<ReadingProgress>(recreated.get(), "resumableProgress")
            assertEquals("reader-async-probe", resumed.bookId)
            assertEquals(7, resumed.sentenceIndex)
            assertEquals(6_000L, resumed.audioOffsetMs)
        } finally {
            recreated.destroy()
        }
    }

    private fun assertPendingLocationCancelled(action: String, activeBookId: String) {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val locationReady = CompletableDeferred<Unit>()
        var restarted = false
        val pending = scope.launch {
            locationReady.await()
            restarted = true
        }
        try {
            PlaybackHub.publish(PlaybackSnapshot(bookId = activeBookId, sentenceIndex = 7, positionMs = 6_000))
            ReflectionHelpers.setField(controller.get(), "readingStartJob", pending)

            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), action)
            locationReady.complete(Unit)

            assertTrue("The latest playback action must cancel the in-flight reading start", pending.isCancelled)
            assertFalse("A late location response must not restart playback", restarted)
        } finally {
            scope.cancel()
            PlaybackHub.publish(PlaybackSnapshot())
            controller.destroy()
        }
    }
}
