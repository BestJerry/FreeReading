package com.freereading.audio

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
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
        val pending = pendingSnapshot()
        PlaybackHub.publish(pending)
        ReflectionHelpers.setField(service, "transitionToken", 8L)

        player.setPlaybackSpeed(1.5f)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1.5f, player.playbackParameters.speed, 0f)
        assertEquals(pending.copy(speed = 1.5f), PlaybackHub.state.value)
    }

    private fun pendingSnapshot() = PlaybackSnapshot(bookId = "book-a", bookTitle = "测试书籍",
        sentenceIndex = 1, sentenceCount = 2, chapterTitle = "本章", text = "丙丁",
        positionMs = 5_000, durationMs = 10_000, chapterProgress = 0.75f,
        chapterSentenceCount = 2, isPreparing = true)
}
