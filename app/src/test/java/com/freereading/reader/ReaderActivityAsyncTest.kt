package com.freereading.reader

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class ReaderActivityAsyncTest {
    @After fun clearAudioState() { PlaybackHub.publish(PlaybackSnapshot()) }

    @Test fun failedForegroundStartRetainsTheDesiredPositionAndPauseThenRetryUseTheRightActions() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create()
        val wasAlive = PlaybackHub.serviceAlive
        val desired = ReadingProgress("reader-async-probe", null, 7, 6_000, 123)
        val old = PlaybackSnapshot(bookId = "reader-async-probe", sentenceIndex = 2,
            isPlaying = true, playbackSessionId = PlaybackHub.newPlaybackSessionId())
        try {
            PlaybackHub.publish(old)
            rejectForegroundStarts(controller.get()) {
                ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "requestAudioStart",
                    ClassParameter.from(ReadingProgress::class.java, desired))
            }
            assertNull(ReflectionHelpers.getField<Int?>(controller.get(), "pendingAudioSentence"))
            assertEquals(desired, ReflectionHelpers.getField<ReadingProgress>(controller.get(), "resumableProgress"))
            assertEquals(ReadingPlaybackIntent.RETRY_AUDIO_START,
                ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(controller.get(), "getReadingPlaybackIntent"))
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowAudio"))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))

            PlaybackHub.serviceAlive = true
            PlaybackHub.publish(old)
            shadowOf(app).clearStartedServices()
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleListening")
            assertEquals(ReadingAudioService.ACTION_TOGGLE, shadowOf(app).nextStartedService?.action)
            assertEquals(desired, ReflectionHelpers.getField<ReadingProgress>(controller.get(), "pendingStoppedProgress"))

            PlaybackHub.publish(old.copy(isPlaying = false))
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleListening")
            val retry = shadowOf(app).nextStartedService
            assertEquals(ReadingAudioService.ACTION_PLAY_BOOK, retry?.action)
            assertEquals(7, retry?.getIntExtra(ReadingAudioService.EXTRA_INDEX, -1))
            assertEquals(6_000L, retry?.getLongExtra(ReadingAudioService.EXTRA_POSITION, -1))
            assertEquals(7, ReflectionHelpers.getField<Int?>(controller.get(), "pendingAudioSentence"))
        } finally {
            PlaybackHub.serviceAlive = wasAlive
            controller.destroy()
        }
    }

    @Test fun failedSavedStartRetainsItsRetryCursorAcrossRecreation() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        val desired = ReadingProgress("reader-async-probe", null, 7, 6_000, 123)
        try {
            ReflectionHelpers.setField(controller.get(), "resumableProgress", desired)
            rejectForegroundStarts(controller.get()) {
                ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "resumeOrPlayFromPage")
            }
            controller.saveInstanceState(saved)
        } finally { controller.destroy() }
        val restarted = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            assertNull(ReflectionHelpers.getField<Int?>(restarted.get(), "pendingAudioSentence"))
            assertEquals(desired, ReflectionHelpers.getField<ReadingProgress>(restarted.get(), "pendingStoppedProgress"))
            assertEquals(desired, ReflectionHelpers.getField<ReadingProgress>(restarted.get(), "resumableProgress"))
            assertEquals(ReadingPlaybackIntent.RETRY_AUDIO_START,
                ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(restarted.get(), "getReadingPlaybackIntent"))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(restarted.get(), "getFollowingAudio"))
        } finally { restarted.destroy() }
    }

    @Test fun aPendingRequestFromAnOldProcessIsDiscardedOnRecreation() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val saved = Bundle().apply {
            putInt("reader_pending_audio_sentence", 7)
            putString("reader_pending_audio_request", "old-process:1")
        }
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create(saved)
        try {
            assertNull(ReflectionHelpers.getField<Int?>(controller.get(), "pendingAudioSentence"))
            assertNull(ReflectionHelpers.getField<String?>(controller.get(), "pendingAudioRequestId"))
        } finally { controller.destroy() }
    }

    @Test fun aRequestedAudioSentenceSurvivesRecreationUntilTheServiceConfirmsItsCursor() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        val requestId = PlaybackHub.newPlaybackSessionId()
        try {
            ReflectionHelpers.setField(controller.get(), "pendingAudioSentence", 7)
            ReflectionHelpers.setField(controller.get(), "pendingAudioRequestId", requestId)
            controller.saveInstanceState(saved)
        } finally { controller.destroy() }
        val recreated = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            assertEquals(7, ReflectionHelpers.getField<Int?>(recreated.get(), "pendingAudioSentence"))
            assertEquals(requestId, ReflectionHelpers.getField<String?>(recreated.get(), "pendingAudioRequestId"))
            ReflectionHelpers.callInstanceMethod<Unit>(recreated.get(), "markManualNavigation")
            assertNull(ReflectionHelpers.getField<Int?>(recreated.get(), "pendingAudioSentence"))
        } finally { recreated.destroy() }
    }

    @Test fun aRecreatedReaderRecognizesANewSessionEvenAfterItHasAdvancedPastTheRequestedSentence() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        val requestId = PlaybackHub.newPlaybackSessionId()
        try {
            ReflectionHelpers.setField(controller.get(), "pendingAudioSentence", 7)
            ReflectionHelpers.setField(controller.get(), "pendingAudioRequestId", requestId)
            controller.saveInstanceState(saved)
        } finally { controller.destroy() }
        val recreated = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(recreated.get(), "acknowledgeAudioStart",
                ClassParameter.from(PlaybackSnapshot::class.java, PlaybackSnapshot(bookId = "reader-async-probe",
                    sentenceIndex = 8, isPlaying = true, playbackSessionId = requestId)))
            assertNull(ReflectionHelpers.getField<Int?>(recreated.get(), "pendingAudioSentence"))
            assertNull(ReflectionHelpers.getField<String?>(recreated.get(), "pendingAudioRequestId"))
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowingAudio"))
        } finally { recreated.destroy() }
    }

    @Test fun manualBrowsingTemporarilyDetachesWithoutDisablingTheAutomaticPagingPreference() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create()
        try {
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))

            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "markManualNavigation")

            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowAudio"))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleAudioFollow")
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))
        } finally { controller.destroy() }
    }

    @Test fun resumingSavedAudioReenablesAutomaticPagingAndKeepsItsChosenPosition() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, "reader-async-probe")).create()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleAudioFollow")
            val saved = ReadingProgress("reader-async-probe", null, 7, 6_000)
            ReflectionHelpers.setField(controller.get(), "resumableProgress", saved)

            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "resumeOrPlayFromPage")

            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowAudio"))
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))
            val started = shadowOf(app).nextStartedService
            assertEquals(ReadingAudioService.ACTION_PLAY_BOOK, started?.action)
            assertEquals(7, started?.getIntExtra(ReadingAudioService.EXTRA_INDEX, -1))
            assertEquals(6_000L, started?.getLongExtra(ReadingAudioService.EXTRA_POSITION, -1))
        } finally { controller.destroy() }
    }

    @Test fun recreationDoesNotReconsumeAStartAfterTheUserClosesFollowButANewResumeRestoresIt() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        PlaybackHub.publish(PlaybackSnapshot(bookId = "reader-async-probe", followRequestId = PlaybackHub.newFollowRequestId()))
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "synchronizeAudio")
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "toggleAudioFollow")
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "getFollowingAudio"))
            controller.saveInstanceState(saved)
        } finally { controller.destroy() }
        val recreated = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(recreated.get(), "synchronizeAudio")
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowingAudio"))
            val nextFollowRequest = PlaybackHub.newFollowRequestId()
            PlaybackHub.publish(PlaybackSnapshot(bookId = "another-book", followRequestId = nextFollowRequest))
            ReflectionHelpers.callInstanceMethod<Unit>(recreated.get(), "synchronizeAudio")
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowingAudio"))
            PlaybackHub.publish(PlaybackSnapshot(bookId = "reader-async-probe", followRequestId = nextFollowRequest))
            ReflectionHelpers.callInstanceMethod<Unit>(recreated.get(), "synchronizeAudio")
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowingAudio"))
        } finally { recreated.destroy() }
    }

    @Test fun recreationKeepsAChosenReadingLocationDetachedWhileRememberingAutomaticPagingIsEnabled() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val intent = ReaderActivity.intent(app, "reader-async-probe")
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).create()
        val saved = Bundle()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "markManualNavigation")
            controller.saveInstanceState(saved)
        } finally { controller.destroy() }
        val recreated = Robolectric.buildActivity(ReaderActivity::class.java, intent).create(saved)
        try {
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowAudio"))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(recreated.get(), "getFollowingAudio"))
            assertEquals(ReadingPlaybackIntent.FROM_READING_LOCATION,
                ReflectionHelpers.callInstanceMethod<ReadingPlaybackIntent>(recreated.get(), "getReadingPlaybackIntent"))
        } finally { recreated.destroy() }
    }

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

    private inline fun rejectForegroundStarts(activity: ReaderActivity, action: () -> Unit) {
        val original: Context = ReflectionHelpers.getField(activity, "mBase")
        val rejecting = object : ContextWrapper(original) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("Injected foreground service start rejection")
            }
        }
        ReflectionHelpers.setField(activity, "mBase", rejecting)
        try { action() } finally { ReflectionHelpers.setField(activity, "mBase", original) }
    }
}
