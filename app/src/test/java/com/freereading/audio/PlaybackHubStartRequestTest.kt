package com.freereading.audio

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import com.freereading.R
import com.freereading.ReadingApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackHubStartRequestTest {
    @After fun clearPlaybackState() {
        PlaybackHub.publish(PlaybackSnapshot())
    }

    @Test fun aManualFollowWatermarkRejectsEarlierSubmittedCommandsButAllowsTheNextStart() {
        val submitted = PlaybackHub.newFollowRequestId()
        val manualOperation = PlaybackHub.newFollowRequestId()
        val nextStart = PlaybackHub.newFollowRequestId()

        assertFalse(PlaybackHub.isNewerFollowRequest(submitted, manualOperation))
        assertFalse(PlaybackHub.isNewerFollowRequest(manualOperation, manualOperation))
        assertTrue(PlaybackHub.isNewerFollowRequest(nextStart, manualOperation))
        assertTrue(PlaybackHub.isNewerFollowRequest(nextStart, "old-process:999"))
        assertFalse(PlaybackHub.isNewerFollowRequest("old-process:999", nextStart))
        val namespace = nextStart.substringBefore(':')
        for (id in listOf(null, "", "$namespace:0", "$namespace:01", "$namespace:-1",
            "$namespace:invalid", "$namespace:${Long.MAX_VALUE}")) {
            assertFalse("Malformed or unissued event: $id", PlaybackHub.isNewerFollowRequest(id, null))
        }
    }

    @Test fun allCommandsThatCanStartSpeechCarryTheirOriginalSubmissionOrder() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val actions = listOf(ReadingAudioService.ACTION_PLAY_BOOK, ReadingAudioService.ACTION_TOGGLE,
            ReadingAudioService.ACTION_NEXT, ReadingAudioService.ACTION_PREVIOUS,
            ReadingAudioService.ACTION_NEXT_CHAPTER, ReadingAudioService.ACTION_PREVIOUS_CHAPTER)
        var earlier: String? = null
        for (action in actions) {
            val command = PlaybackHub.command(app, action)
            val submitted = command.getStringExtra(ReadingAudioService.EXTRA_FOLLOW_REQUEST_ID)
            assertTrue("Command must be ordered before submission: $action",
                PlaybackHub.isNewerFollowRequest(submitted, earlier))
            assertEquals(submitted, PlaybackHub.followRequestId(command))
            earlier = submitted
        }
        assertFalse(PlaybackHub.command(app, ReadingAudioService.ACTION_SEEK_BY)
            .hasExtra(ReadingAudioService.EXTRA_FOLLOW_REQUEST_ID))
    }

    @Test fun anEarlierOutstandingStartCannotConfirmANewerRequest() {
        val earlier = PlaybackHub.newPlaybackSessionId()
        val requested = PlaybackHub.newPlaybackSessionId()

        assertNotEquals(earlier, requested)
        assertTrue(PlaybackHub.isCurrentPlaybackSessionId(earlier))
        assertTrue(PlaybackHub.isCurrentPlaybackSessionId(requested))
        assertFalse(PlaybackHub.isPlaybackStartConfirmed(requested, earlier))
    }

    @Test fun theRequestedSessionOrALaterSessionConfirmsAStartEvenAfterItsFirstSentenceWasMissed() {
        val requested = PlaybackHub.newPlaybackSessionId()
        val later = PlaybackHub.newPlaybackSessionId()

        assertTrue(PlaybackHub.isPlaybackStartConfirmed(requested, requested))
        assertTrue(PlaybackHub.isPlaybackStartConfirmed(requested, later))
    }

    @Test fun unknownProcessNamespacesAndMalformedOrUnissuedIdsCannotConfirmAStart() {
        val issued = PlaybackHub.newPlaybackSessionId()
        val namespace = issued.substringBefore(':')
        val invalid = listOf(null, "", "another-process:1", "$namespace:0", "$namespace:-1",
            "$namespace:not-a-number", "$namespace:01", "$namespace:1:2", "$namespace:${Long.MAX_VALUE}")

        for (id in invalid) {
            assertFalse("Invalid ID must not belong to this process: $id", PlaybackHub.isCurrentPlaybackSessionId(id))
            assertFalse(PlaybackHub.isPlaybackStartConfirmed(id, issued))
            assertFalse(PlaybackHub.isPlaybackStartConfirmed(issued, id))
        }
    }

    @Test fun anExplicitRequestIdIsSentUnchangedInTheAuthorizedStartIntent() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        val requested = PlaybackHub.newPlaybackSessionId()

        assertTrue(PlaybackHub.playBook(context, "book-a", 7, 6_000, requested))

        val started = requireNotNull(context.started)
        assertEquals(ReadingAudioService.ACTION_PLAY_BOOK, started.action)
        assertEquals("book-a", started.getStringExtra(ReadingAudioService.EXTRA_BOOK_ID))
        assertEquals(7, started.getIntExtra(ReadingAudioService.EXTRA_INDEX, -1))
        assertEquals(6_000L, started.getLongExtra(ReadingAudioService.EXTRA_POSITION, -1))
        assertEquals(requested, started.getStringExtra(ReadingAudioService.EXTRA_PLAYBACK_SESSION_ID))
        assertTrue(PlaybackHub.isAuthorizedCommand(started))
    }

    @Test fun aStartWithoutAnExplicitRequestIdAllocatesACurrentProcessId() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        val before = PlaybackHub.newPlaybackSessionId()

        assertTrue(PlaybackHub.playBook(context, "book-a", 2))

        val requested = requireNotNull(context.started)
            .getStringExtra(ReadingAudioService.EXTRA_PLAYBACK_SESSION_ID)
        assertTrue(PlaybackHub.isCurrentPlaybackSessionId(requested))
        assertNotEquals(before, requested)
        assertTrue(PlaybackHub.isPlaybackStartConfirmed(before, requested))
        assertFalse(PlaybackHub.isPlaybackStartConfirmed(requested, before))
    }

    @Test fun aRejectedPlatformStartReturnsFalseAndRetainsTheOldAcceptedSession() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication(), IllegalStateException("start rejected"))
        val old = PlaybackSnapshot(bookId = "book-a", sentenceIndex = 2, isPlaying = true,
            playbackSessionId = PlaybackHub.newPlaybackSessionId())
        PlaybackHub.publish(old)
        val requested = PlaybackHub.newPlaybackSessionId()

        assertFalse(PlaybackHub.playBook(context, "book-a", 7, 6_500, requested))

        assertEquals(old.copy(isPlaying = false, isPreparing = false,
            error = context.getString(R.string.audio_start_failed),
            startFailure = PlaybackStartFailure(requested, "book-a", 7, 6_500)), PlaybackHub.state.value)
        assertFalse(PlaybackHub.isPlaybackStartConfirmed(requested, PlaybackHub.state.value.playbackSessionId))
    }

    @Test fun retryingAfterARejectedStartSendsANewIdWithoutClaimingItHasAlreadyBeenAccepted() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val rejected = CapturingStartContext(app, IllegalStateException("start rejected"))
        val oldSessionId = PlaybackHub.newPlaybackSessionId()
        PlaybackHub.publish(PlaybackSnapshot(bookId = "book-a", sentenceIndex = 2,
            playbackSessionId = oldSessionId))
        val failedRequestId = PlaybackHub.newPlaybackSessionId()
        assertFalse(PlaybackHub.playBook(rejected, "book-a", 7, 6_500, failedRequestId))
        val afterFailure = PlaybackHub.state.value
        val retry = CapturingStartContext(app)
        val retryId = PlaybackHub.newPlaybackSessionId()

        assertTrue(PlaybackHub.playBook(retry, "book-a", 7, 6_500, retryId))

        assertEquals(retryId, requireNotNull(retry.started).getStringExtra(ReadingAudioService.EXTRA_PLAYBACK_SESSION_ID))
        assertEquals(afterFailure, PlaybackHub.state.value)
        assertEquals(oldSessionId, PlaybackHub.state.value.playbackSessionId)
        assertEquals(failedRequestId, PlaybackHub.state.value.startFailure?.requestId)
        assertFalse(PlaybackHub.isPlaybackStartConfirmed(retryId, PlaybackHub.state.value.playbackSessionId))
    }

    @Test fun stoppingAndDestroyingTheServiceKeepsARejectedRequestAvailableToAnAbsentReader() {
        val controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        val speed = PlaybackHub.state.value.speed
        val oldSessionId = PlaybackHub.newPlaybackSessionId()
        val failure = PlaybackStartFailure(PlaybackHub.newPlaybackSessionId(), "book-a", 7, 6_500)
        val emptyWithFailure = PlaybackSnapshot(speed = speed, startFailure = failure)
        try {
            PlaybackHub.publish(PlaybackSnapshot(bookId = "book-a", sentenceIndex = 2, isPlaying = true,
                speed = speed, error = "start rejected", playbackSessionId = oldSessionId,
                startFailure = failure))

            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "stopPlayback")
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(emptyWithFailure, PlaybackHub.state.value)
        } finally { controller.destroy() }

        assertEquals(emptyWithFailure, PlaybackHub.state.value)
        assertFalse(PlaybackHub.serviceAlive)
    }

    private class CapturingStartContext(base: Context, private val failure: RuntimeException? = null) : ContextWrapper(base) {
        var started: Intent? = null
            private set

        override fun startForegroundService(service: Intent): ComponentName {
            failure?.let { throw it }
            started = service
            return ComponentName(this, ReadingAudioService::class.java)
        }
    }
}
