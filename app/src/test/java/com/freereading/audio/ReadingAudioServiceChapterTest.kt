package com.freereading.audio

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual commands, generation cancellation and player queues, with deterministic local WAVs. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class, shadows = [ServiceVoiceTtsShadow::class])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ReadingAudioServiceChapterTest {
    private lateinit var app: ReadingApplication
    private lateinit var context: ChapterCommandContext
    private lateinit var controller: ServiceController<ReadingAudioService>
    private lateinit var service: ReadingAudioService
    private lateinit var player: ExoPlayer
    private lateinit var scheduler: TestCoroutineScheduler
    private var startId = 0

    @Before fun createService() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        app.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.cacheDir, "speech").deleteRecursively()
        ServiceVoiceTtsShadow.resetFixture()
        ServiceVoiceTtsShadow.holdSynthesis = true
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "local.engine" })
        PlaybackHub.publish(PlaybackSnapshot())
        PlaybackHub.publishVoices(TtsVoiceUiState())
        context = ChapterCommandContext(app)
        controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        service = controller.get()
        player = ReflectionHelpers.getField(service, "player")
        drain()
    }

    @After fun releaseService() {
        ReflectionHelpers.setField(service, "book", null)
        controller.destroy()
        scheduler.runCurrent()
        app.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.cacheDir, "speech").deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun nextChapterPublishesTheChapterStartAndStartsFromAPausedSession() {
        activeFixture(index = 3)
        val sessionId = PlaybackHub.state.value.playbackSessionId
        TtsVoicePreferences(app).select("zh", TtsVoiceSelection.voiceId("local.engine", "chosen"))

        nextChapter()

        assertEquals(5, PlaybackHub.state.value.sentenceIndex)
        assertEquals(0L, PlaybackHub.state.value.positionMs)
        assertEquals(0f, PlaybackHub.state.value.chapterProgress, 0f)
        assertEquals("章节7", PlaybackHub.state.value.chapterTitle)
        assertTrue(PlaybackHub.state.value.isPreparing)
        assertTrue(player.playWhenReady)
        assertEquals(0, player.mediaItemCount)
        assertEquals(sessionId, PlaybackHub.state.value.playbackSessionId)
        val followRequest = PlaybackHub.state.value.followRequestId
        assertNotNull(followRequest)
        assertEquals(1.25f, player.playbackParameters.speed, 0f)
        await("chosen voice chapter start") { ServiceVoiceTtsShadow.calls.isNotEmpty() }
        assertEquals("句子5。", ServiceVoiceTtsShadow.calls.first().text)
        assertEquals("chosen", ServiceVoiceTtsShadow.calls.first().voice)

        pause()
        assertEquals(followRequest, PlaybackHub.state.value.followRequestId)
        ServiceVoiceTtsShadow.completePending()
        await("new chapter queue") { player.currentMediaItem?.mediaId == "5" }
        assertEquals(0L, player.currentPosition)
        assertEquals(20_000L, PlaybackHub.state.value.durationMs)
        assertEquals(sessionId, PlaybackHub.state.value.playbackSessionId)
        assertEquals(1.25f, player.playbackParameters.speed, 0f)
    }

    @Test fun previousChapterFromTheMiddleGoesToThePreviousChapterRatherThanRestartingThisOne() {
        activeFixture(index = 4)

        previousChapter()

        assertEquals(0, PlaybackHub.state.value.sentenceIndex)
        assertEquals(0L, PlaybackHub.state.value.positionMs)
        assertTrue(player.playWhenReady)
        assertNotNull(PlaybackHub.state.value.followRequestId)
        assertFalse(PlaybackHub.state.value.canGoToPreviousChapter)
        assertTrue(PlaybackHub.state.value.canGoToNextChapter)
        await("previous chapter text") { ServiceVoiceTtsShadow.calls.isNotEmpty() }
        assertEquals("句子0。", ServiceVoiceTtsShadow.calls.first().text)
    }

    @Test fun theFirstChapterHasNoPreviousTargetAndDoesNotResumeOrRestartAudio() {
        activeFixture(index = 1)
        val before = PlaybackHub.state.value
        val item = player.currentMediaItem

        previousChapter()
        drain()

        assertEquals(before, PlaybackHub.state.value)
        assertEquals(item, player.currentMediaItem)
        assertEquals(6_000L, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertFalse(before.canGoToPreviousChapter)
        assertTrue(ServiceVoiceTtsShadow.calls.isEmpty())
    }

    @Test fun theFinalChapterHasNoNextTargetAndDoesNotResumeOrRestartAudio() {
        activeFixture(index = 7)
        val before = PlaybackHub.state.value
        val item = player.currentMediaItem

        nextChapter()
        drain()

        assertEquals(before, PlaybackHub.state.value)
        assertEquals(item, player.currentMediaItem)
        assertFalse(player.playWhenReady)
        assertFalse(before.canGoToNextChapter)
        assertTrue(ServiceVoiceTtsShadow.calls.isEmpty())
    }

    @Test fun pauseAfterAChapterCommandRemainsTheLatestIntentWhenItsClipArrives() {
        activeFixture(index = 3)
        nextChapter()
        await("held chapter clip") { ServiceVoiceTtsShadow.calls.isNotEmpty() }
        assertTrue(player.playWhenReady)

        pause()
        ServiceVoiceTtsShadow.completePending()
        await("paused chapter clip") { player.currentMediaItem?.mediaId == "5" }

        assertFalse(player.playWhenReady)
        assertEquals(5, PlaybackHub.state.value.sentenceIndex)
        assertEquals(0L, player.currentPosition)
    }

    @Test fun rapidCommandsUseThePendingChapterAndOldSynthesisCannotWinTheQueue() {
        activeFixture(index = 3)
        val sessionId = PlaybackHub.state.value.playbackSessionId
        nextChapter()
        await("first target") { ServiceVoiceTtsShadow.calls.size == 1 }
        assertEquals(5, PlaybackHub.state.value.sentenceIndex)
        val firstFollow = PlaybackHub.state.value.followRequestId

        nextChapter()
        assertEquals(7, PlaybackHub.state.value.sentenceIndex)
        assertNotEquals(firstFollow, PlaybackHub.state.value.followRequestId)
        assertFalse(PlaybackHub.state.value.canGoToNextChapter)
        await("second target") { ServiceVoiceTtsShadow.calls.size == 2 }

        previousChapter()
        assertEquals(5, PlaybackHub.state.value.sentenceIndex)
        assertTrue(PlaybackHub.state.value.canGoToNextChapter)
        await("latest reversed target") { ServiceVoiceTtsShadow.calls.size == 3 }
        assertEquals(listOf("句子5。", "句子7。", "句子5。"), ServiceVoiceTtsShadow.calls.map { it.text })
        pause()
        ServiceVoiceTtsShadow.completePending()
        await("only latest chapter queued") { player.currentMediaItem?.mediaId == "5" }

        assertEquals(sessionId, PlaybackHub.state.value.playbackSessionId)
        assertFalse(player.playWhenReady)
        assertEquals(0L, player.currentPosition)
        assertTrue((0 until player.mediaItemCount).all { player.getMediaItemAt(it).mediaId != "7" })
    }

    @Test fun chapterJumpCancelsOldVoicePrefetchBeforePreparingItsTarget() {
        activeFixture(index = 3)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "startPrefetch")
        await("held old prefetch") { ServiceVoiceTtsShadow.calls.size == 1 }
        assertEquals("句子5。", ServiceVoiceTtsShadow.calls.single().text)

        previousChapter()
        await("previous chapter after old prefetch joined") { ServiceVoiceTtsShadow.calls.size == 2 }

        assertEquals("句子0。", ServiceVoiceTtsShadow.calls[1].text)
        assertEquals(0, PlaybackHub.state.value.sentenceIndex)
        pause()
        ServiceVoiceTtsShadow.completePending()
        await("previous chapter only") { player.currentMediaItem?.mediaId == "0" }
        assertTrue((0 until player.mediaItemCount).all { player.getMediaItemAt(it).mediaId != "5" })
    }

    @Test fun capabilityFlagsFollowRequestedChaptersEvenBeforeTheirAudioIsReady() {
        activeFixture(index = 0)
        assertFalse(PlaybackHub.state.value.canGoToPreviousChapter)
        assertTrue(PlaybackHub.state.value.canGoToNextChapter)

        nextChapter()
        assertEquals(2, PlaybackHub.state.value.sentenceIndex)
        assertTrue(PlaybackHub.state.value.canGoToPreviousChapter)
        assertTrue(PlaybackHub.state.value.canGoToNextChapter)
        nextChapter()
        assertEquals(5, PlaybackHub.state.value.sentenceIndex)
        nextChapter()
        assertEquals(7, PlaybackHub.state.value.sentenceIndex)
        assertTrue(PlaybackHub.state.value.canGoToPreviousChapter)
        assertFalse(PlaybackHub.state.value.canGoToNextChapter)
    }

    @Test fun aStaleOrUnauthorizedBookCommandCannotControlTheActiveBook() {
        activeFixture(index = 3)
        val before = PlaybackHub.state.value
        val item = player.currentMediaItem
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_NEXT_CHAPTER)
            .putExtra(ReadingAudioService.EXTRA_BOOK_ID, "book-b"))
        send(Intent(app, ReadingAudioService::class.java).setAction(ReadingAudioService.ACTION_PREVIOUS_CHAPTER)
            .putExtra(ReadingAudioService.EXTRA_BOOK_ID, "book-a"))
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_NEXT_CHAPTER))
        drain()

        assertEquals(before, PlaybackHub.state.value)
        assertEquals(item, player.currentMediaItem)
        assertFalse(player.playWhenReady)
        assertTrue(ServiceVoiceTtsShadow.calls.isEmpty())
    }

    @Test fun hubDoesNotStartAServiceForAnInactiveOrDifferentBook() {
        activeFixture(index = 3)
        PlaybackHub.nextChapter(context, "book-b")
        PlaybackHub.previousChapter(context, "book-b")
        PlaybackHub.serviceAlive = false
        PlaybackHub.nextChapter(context, "book-a")

        assertEquals(0, context.starts)
    }

    @Test fun existingMediaNextAndPreviousStillMoveOneSentenceWithoutStartingPlayback() {
        activeFixture(index = 3)
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_NEXT))
        assertEquals(4, PlaybackHub.state.value.sentenceIndex)
        assertFalse(player.playWhenReady)
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_PREVIOUS))

        assertEquals(3, PlaybackHub.state.value.sentenceIndex)
        assertFalse(player.playWhenReady)
        assertNull(PlaybackHub.state.value.followRequestId)
    }

    @Test fun explicitSentenceSkipWhilePlayingRequestsFollowAtItsNewTarget() {
        activeFixture(index = 3)
        player.playWhenReady = true

        send(PlaybackHub.command(app, ReadingAudioService.ACTION_NEXT))

        assertEquals(4, PlaybackHub.state.value.sentenceIndex)
        assertTrue(player.playWhenReady)
        val first = requireNotNull(PlaybackHub.state.value.followRequestId)
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_PREVIOUS))
        assertEquals(3, PlaybackHub.state.value.sentenceIndex)
        assertNotEquals(first, PlaybackHub.state.value.followRequestId)
    }

    @Test fun retryingAnEmptyQueueRestoresFollowEvenWhenThePreviousPlayIntentWasAlreadyTrue() {
        activeFixture(index = 3)
        ReflectionHelpers.setField(service, "transitionToken", -1L)
        player.clearMediaItems()
        player.playWhenReady = true
        drain()
        ReflectionHelpers.setField(service, "transitionToken", null)
        val failed = PlaybackHub.state.value.copy(error = "上次准备失败", followRequestId = "failed-start")
        PlaybackHub.publish(failed)
        val session: MediaSession = ReflectionHelpers.getField(service, "session")

        session.player.play()

        assertNotEquals(failed.followRequestId, PlaybackHub.state.value.followRequestId)
        assertEquals(failed.playbackSessionId, PlaybackHub.state.value.playbackSessionId)
        assertEquals(3, PlaybackHub.state.value.sentenceIndex)
        assertTrue(player.playWhenReady)
    }

    @Test fun stopDuringPreparationClearsChapterCapabilitiesAndLateCallbacksCannotRestoreThem() {
        activeFixture(index = 3)
        nextChapter()
        await("held chapter") { ServiceVoiceTtsShadow.calls.isNotEmpty() }
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_STOP))
        await("stopped chapter session") { PlaybackHub.state.value.bookId == null }
        ServiceVoiceTtsShadow.completePending()
        drain()

        assertFalse(PlaybackHub.state.value.canGoToPreviousChapter)
        assertFalse(PlaybackHub.state.value.canGoToNextChapter)
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    private fun activeFixture(index: Int) {
        val chapterIds = listOf(0, 0, 3, 3, 3, 7, 7, 10)
        val sentences = chapterIds.mapIndexed { sentenceIndex, chapter ->
            SpeechSentence(sentenceIndex, "句子$sentenceIndex。", "{}", chapter, "章节$chapter")
        }
        val fixture = SpeechBook(BookRecord("book-a", "章节测试书", "作者", "zh", 0, sentences.size), sentences)
        val chapters = ChapterProgressIndex(sentences)
        ReflectionHelpers.setField(service, "book", fixture)
        ReflectionHelpers.setField(service, "chapterProgressIndex", chapters)
        ReflectionHelpers.setField(service, "transitionToken", -1L)
        val old = File(app.cacheDir, "chapter-old.wav").apply { writeBytes(ServiceVoiceTtsShadow.wav(10_000)) }
        val queued = listOf(index, index + 1).filter { it in sentences.indices }
        val clips: MutableMap<Int, SynthesizedSentence> = ReflectionHelpers.getField(service, "clips")
        queued.forEach { clips[it] = SynthesizedSentence(old, 10_000) }
        player.setMediaItems(queued.map {
            MediaItem.Builder().setMediaId(it.toString()).setUri(Uri.fromFile(old)).build()
        }, 0, 6_000)
        player.setPlaybackSpeed(1.25f)
        player.playWhenReady = false
        drain()
        ReflectionHelpers.setField(service, "transitionToken", null)
        PlaybackHub.publish(PlaybackSnapshot(bookId = "book-a", bookTitle = fixture.book.title,
            sentenceIndex = index, sentenceCount = sentences.size, text = sentences[index].text,
            chapterTitle = sentences[index].chapterTitle, positionMs = 6_000, durationMs = 10_000,
            speed = 1.25f, playbackSessionId = PlaybackHub.newPlaybackSessionId(),
            canGoToPreviousChapter = chapters.previousChapterStart(index) != null,
            canGoToNextChapter = chapters.nextChapterStart(index) != null))
    }

    private fun nextChapter() {
        PlaybackHub.nextChapter(context, "book-a")
        val intent = requireNotNull(context.started)
        assertEquals(ReadingAudioService.ACTION_NEXT_CHAPTER, intent.action)
        assertEquals("book-a", intent.getStringExtra(ReadingAudioService.EXTRA_BOOK_ID))
        assertTrue(PlaybackHub.isAuthorizedCommand(intent))
        send(intent)
    }
    private fun previousChapter() {
        PlaybackHub.previousChapter(context, "book-a")
        val intent = requireNotNull(context.started)
        assertEquals(ReadingAudioService.ACTION_PREVIOUS_CHAPTER, intent.action)
        assertTrue(PlaybackHub.isAuthorizedCommand(intent))
        send(intent)
    }
    private fun pause() = send(PlaybackHub.command(app, ReadingAudioService.ACTION_TOGGLE))
    private fun send(intent: Intent) { service.onStartCommand(intent, 0, ++startId) }
    private fun drain() { scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            drain()
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for $description")
            Thread.sleep(2)
        }
        drain()
    }

    private class ChapterCommandContext(base: Context) : ContextWrapper(base) {
        var started: Intent? = null
        var starts = 0
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName {
            started = service
            starts += 1
            return ComponentName(this, ReadingAudioService::class.java)
        }
    }
}
