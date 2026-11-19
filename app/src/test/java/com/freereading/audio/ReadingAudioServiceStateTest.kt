package com.freereading.audio

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Real service/player state changes, without preparing audio or initializing any TTS engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ReadingAudioServiceStateTest {
    private lateinit var controller: ServiceController<ReadingAudioService>
    private lateinit var service: ReadingAudioService
    private lateinit var player: ExoPlayer

    @Before fun createEmptyPlayerService() {
        controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        service = controller.get()
        player = ReflectionHelpers.getField(service, "player")
        val sentences = listOf(
            SpeechSentence(0, "甲乙", "{}", 0, "本章"),
            SpeechSentence(1, "丙丁", "{}", 0, "本章"),
        )
        val book = SpeechBook(BookRecord("book-a", "测试书籍", "作者", "zh", 0, sentences.size), sentences)
        ReflectionHelpers.setField(service, "book", book)
        ReflectionHelpers.setField(service, "chapterProgressIndex", ChapterProgressIndex(sentences))
        assertEquals(0, player.mediaItemCount)
    }

    @After fun releaseServiceWithoutSavingTheFixtureBook() {
        ReflectionHelpers.setField(service, "book", null)
        ReflectionHelpers.setField(service, "transitionToken", null)
        controller.destroy()
    }

    @Test fun failedTransitionAndPeriodicTicksPreserveTheRequestedCursorWithAnEmptyPlayer() {
        val requested = pendingSnapshot()
        PlaybackHub.publish(requested)
        ReflectionHelpers.setField(service, "transitionToken", 7L)

        ReflectionHelpers.callInstanceMethod<Unit>(service, "reportFailure",
            ClassParameter.from(Exception::class.java, AudioFailure("测试语音合成失败")))
        val afterFailure = PlaybackHub.state.value
        assertEquals(requested.copy(isPreparing = false, error = "测试语音合成失败"), afterFailure)

        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(0, player.mediaItemCount)
        assertEquals(afterFailure, PlaybackHub.state.value)
    }

    @Test fun changingActualPlayerSpeedWhilePreparingPublishesSpeedWithoutChangingTheCursor() {
        val pending = pendingSnapshot().copy(followRequestId = "accepted-start")
        PlaybackHub.publish(pending)
        ReflectionHelpers.setField(service, "transitionToken", 8L)

        player.setPlaybackSpeed(1.5f)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1.5f, player.playbackParameters.speed, 0f)
        assertEquals(pending.copy(speed = 1.5f), PlaybackHub.state.value)
    }

    @Test fun systemMediaResumeRestoresFollowOnceWithoutReplacingTheAcceptedSession() {
        val accepted = pendingSnapshot().copy(playbackSessionId = PlaybackHub.newPlaybackSessionId(),
            followRequestId = "previous-start")
        PlaybackHub.publish(accepted)
        ReflectionHelpers.setField(service, "transitionToken", 9L)
        val session: MediaSession = ReflectionHelpers.getField(service, "session")

        session.player.play()

        val resumed = requireNotNull(PlaybackHub.state.value.followRequestId)
        assertNotEquals(accepted.followRequestId, resumed)
        assertEquals(accepted.playbackSessionId, PlaybackHub.state.value.playbackSessionId)
        session.player.play()
        assertEquals("Duplicate play while preparing is not another start", resumed,
            PlaybackHub.state.value.followRequestId)
        session.player.pause()
        assertEquals(resumed, PlaybackHub.state.value.followRequestId)
        session.player.playWhenReady = true
        assertNotEquals(resumed, PlaybackHub.state.value.followRequestId)
        assertEquals(accepted.playbackSessionId, PlaybackHub.state.value.playbackSessionId)
    }

    @Test fun miniPlayerToggleRestoresFollowOnResumeAndLeavesPauseAlone() {
        val accepted = pendingSnapshot().copy(followRequestId = "previous-start")
        PlaybackHub.publish(accepted)
        ReflectionHelpers.setField(service, "transitionToken", 10L)
        val command = PlaybackHub.command(service, ReadingAudioService.ACTION_TOGGLE)

        service.onStartCommand(command, 0, 1)

        val resumed = requireNotNull(PlaybackHub.state.value.followRequestId)
        assertNotEquals(accepted.followRequestId, resumed)
        service.onStartCommand(command, 0, 2)
        assertEquals(resumed, PlaybackHub.state.value.followRequestId)
    }

    @Test fun aNewBookStartPublishesFollowBeforeTheSavedSentenceIsResolved() {
        PlaybackHub.publish(pendingSnapshot().copy(followRequestId = "old-start"))
        val sessionId = PlaybackHub.newPlaybackSessionId()

        ReflectionHelpers.callInstanceMethod<Unit>(service, "playBook",
            ClassParameter.from(String::class.java, "another-book"),
            ClassParameter.from(Int::class.javaPrimitiveType, -1),
            ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            ClassParameter.from(String::class.java, sessionId),
            ClassParameter.from(String::class.java, PlaybackHub.newFollowRequestId()))

        val starting = PlaybackHub.state.value
        assertEquals("another-book", starting.bookId)
        assertEquals(sessionId, starting.playbackSessionId)
        assertEquals(0, starting.sentenceCount)
        assertNotNull(starting.followRequestId)
        assertNotEquals("old-start", starting.followRequestId)
    }

    private fun pendingSnapshot() = PlaybackSnapshot(bookId = "book-a", bookTitle = "测试书籍",
        sentenceIndex = 1, sentenceCount = 2, chapterTitle = "本章", text = "丙丁",
        positionMs = 5_000, durationMs = 10_000, chapterProgress = 0.75f,
        chapterSentenceCount = 2, isPreparing = true)
}
