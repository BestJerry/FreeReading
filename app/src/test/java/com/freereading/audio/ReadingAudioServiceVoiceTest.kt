package com.freereading.audio

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.freereading.R
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers

/** Exercises authorized intents, the real service generation, cache mutex and ExoPlayer queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class, shadows = [ServiceVoiceTtsShadow::class])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ReadingAudioServiceVoiceTest {
    private lateinit var app: ReadingApplication
    private lateinit var context: VoiceCommandContext
    private lateinit var controller: ServiceController<ReadingAudioService>
    private lateinit var service: ReadingAudioService
    private lateinit var player: ExoPlayer
    private lateinit var scheduler: TestCoroutineScheduler
    private var destroyed = false
    private var startId = 0
    private val chosenId = TtsVoiceSelection.voiceId("local.engine", "chosen")
    private val automaticId = TtsVoiceSelection.voiceId("local.engine", "automatic")

    @Before fun createService() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        app.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.cacheDir, "speech").deleteRecursively()
        ServiceVoiceTtsShadow.resetFixture()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "local.engine" })
        PlaybackHub.publish(PlaybackSnapshot())
        PlaybackHub.publishVoices(TtsVoiceUiState())
        context = VoiceCommandContext(app)
        controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        service = controller.get()
        player = ReflectionHelpers.getField(service, "player")
        drain()
    }

    @After fun releaseService() {
        if (!destroyed) destroyService()
        app.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.cacheDir, "speech").deleteRecursively()
        PlaybackHub.publishVoices(TtsVoiceUiState())
        Dispatchers.resetMain()
    }

    @Test fun idleCatalogUsesARegularServiceWithoutPlaybackOrForegroundNotificationAndSurvivesDestroy() {
        load()
        await("voice catalog") { !PlaybackHub.voiceState.value.loading }

        val result = PlaybackHub.voiceState.value
        assertEquals(2, result.catalog?.voices?.size)
        assertNull(PlaybackHub.state.value.bookId)
        assertEquals(0, player.mediaItemCount)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertEquals(1, shadowOf(service).stopSelfId)
        assertEquals(0, context.foregroundStarts)
        destroyService()
        assertEquals(result, PlaybackHub.voiceState.value)
    }

    @Test fun choosingBeforePlaybackOnlySavesThePreferenceAndAutomaticClearsIt() {
        select(chosenId)
        await("saved choice") { !PlaybackHub.voiceState.value.switching }
        assertEquals(chosenId, TtsVoicePreferences(app).selected("zh"))
        assertEquals(chosenId, PlaybackHub.voiceState.value.catalog?.selectedVoiceId)
        assertTrue(ServiceVoiceTtsShadow.calls.isEmpty())
        assertEquals(0, player.mediaItemCount)
        assertNull(PlaybackHub.state.value.bookId)

        select(null)
        await("automatic choice") { !PlaybackHub.voiceState.value.switching }
        assertNull(TtsVoicePreferences(app).selected("zh"))
        assertEquals(automaticId, PlaybackHub.voiceState.value.catalog?.effectiveVoiceId)
    }

    @Test fun pausedSwitchKeepsTheSentenceSessionAndSpeedAndMapsItsRelativePosition() {
        activeFixture(playing = false)
        val sessionId = PlaybackHub.state.value.playbackSessionId
        ServiceVoiceTtsShadow.holdSynthesis = true
        select(chosenId)
        await("new voice synthesis") { ServiceVoiceTtsShadow.calls.isNotEmpty() }

        assertEquals(1, PlaybackHub.state.value.sentenceIndex)
        assertEquals(5_000L, PlaybackHub.state.value.positionMs)
        assertEquals(0, player.mediaItemCount)
        ServiceVoiceTtsShadow.completePending()
        await("rebuilt sentence") { !PlaybackHub.voiceState.value.switching }

        assertEquals("1", player.currentMediaItem?.mediaId)
        assertEquals(10_000L, player.currentPosition)
        assertEquals(20_000L, PlaybackHub.state.value.durationMs)
        assertEquals(sessionId, PlaybackHub.state.value.playbackSessionId)
        assertFalse(player.playWhenReady)
        assertEquals(1.25f, player.playbackParameters.speed, 0f)
        assertEquals(1, player.mediaItemCount) // The next speculative synthesis is held.
        assertEquals("chosen", ServiceVoiceTtsShadow.calls.first().voice)
        assertTrue(player.currentMediaItem!!.localConfiguration!!.uri.path!!.endsWith(".wav"))
        assertFalse(player.currentMediaItem!!.localConfiguration!!.uri.path!!.endsWith("old.wav"))
    }

    @Test fun pauseAndSpeedDuringSwitchRemainTheLatestPlaybackIntent() {
        activeFixture(playing = true)
        ServiceVoiceTtsShadow.holdSynthesis = true
        select(chosenId)
        await("held new voice") { ServiceVoiceTtsShadow.calls.isNotEmpty() }

        send(PlaybackHub.command(app, ReadingAudioService.ACTION_TOGGLE))
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_SPEED)
            .putExtra(ReadingAudioService.EXTRA_SPEED, 1.75f))
        ServiceVoiceTtsShadow.completePending()
        await("paused rebuilt sentence") { !PlaybackHub.voiceState.value.switching }

        assertFalse(player.playWhenReady)
        assertEquals(1.75f, player.playbackParameters.speed, 0f)
        assertEquals(1.75f, PlaybackHub.state.value.speed, 0f)
        assertEquals(1, PlaybackHub.state.value.sentenceIndex)
    }

    @Test fun switchingCancelsAndJoinsOldVoicePrefetchBeforeSynthesizingTheCurrentSentence() {
        activeFixture(playing = false)
        ServiceVoiceTtsShadow.holdSynthesis = true
        ReflectionHelpers.callInstanceMethod<Unit>(service, "startPrefetch")
        await("old voice prefetch") { ServiceVoiceTtsShadow.calls.size == 1 }
        val previousEngine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertEquals("automatic", ServiceVoiceTtsShadow.calls.single().voice)

        select(chosenId)
        await("replacement synthesis") { ServiceVoiceTtsShadow.calls.size == 2 }
        assertTrue(shadowOf(previousEngine).isStopped)
        assertEquals("chosen", ServiceVoiceTtsShadow.calls[1].voice)
        assertEquals("句子1。", ServiceVoiceTtsShadow.calls[1].text)
        ServiceVoiceTtsShadow.completePending() // Includes a cancelled old callback, which must be harmless.
        await("selected voice queue") { !PlaybackHub.voiceState.value.switching }
        assertEquals("1", player.currentMediaItem?.mediaId)
        assertEquals(1, player.mediaItemCount)
        assertEquals(chosenId, PlaybackHub.voiceState.value.catalog?.selectedVoiceId)
    }

    @Test fun aNewerSelectionCancelsOldSynthesisAndOnlyItsResultAndQueueWin() {
        activeFixture(playing = false)
        ServiceVoiceTtsShadow.holdSynthesis = true
        select(chosenId)
        val oldRequest = PlaybackHub.voiceState.value.requestId
        await("first selected voice") { ServiceVoiceTtsShadow.calls.size == 1 }

        select(null)
        val latestRequest = PlaybackHub.voiceState.value.requestId
        await("replacement automatic voice") { ServiceVoiceTtsShadow.calls.size == 2 }
        ServiceVoiceTtsShadow.completePending()
        await("latest selection") { !PlaybackHub.voiceState.value.switching }

        assertNotEquals(oldRequest, latestRequest)
        assertEquals(latestRequest, PlaybackHub.voiceState.value.requestId)
        assertNull(PlaybackHub.voiceState.value.catalog?.selectedVoiceId)
        assertEquals(automaticId, PlaybackHub.voiceState.value.catalog?.effectiveVoiceId)
        assertEquals("automatic", ServiceVoiceTtsShadow.calls[1].voice)
        assertEquals(5_000L, player.currentPosition)
        assertFalse(player.playWhenReady)
    }

    @Test fun anotherPausedBookRejectsSelectionWithoutChangingItsCursorQueueOrPreferences() {
        activeFixture(playing = false)
        val before = PlaybackHub.state.value
        val oldItem = player.currentMediaItem
        select(chosenId, bookId = "book-b")

        assertEquals(before, PlaybackHub.state.value)
        assertEquals(oldItem, player.currentMediaItem)
        assertNull(TtsVoicePreferences(app).selected("zh"))
        assertFalse(PlaybackHub.voiceState.value.switching)
        assertEquals(app.getString(R.string.audio_voice_other_book), PlaybackHub.voiceState.value.error)
        assertTrue(ServiceVoiceTtsShadow.calls.isEmpty())
    }

    @Test fun aStaleOrUnauthorizedCatalogIntentCannotReplaceTheLatestRequest() {
        PlaybackHub.loadVoices(context, "book-a", "zh")
        val stale = context.started!!
        PlaybackHub.loadVoices(context, "book-b", "zh")
        send(context.started!!)
        send(stale)
        val unauthorized = Intent(app, ReadingAudioService::class.java)
            .setAction(ReadingAudioService.ACTION_SELECT_VOICE)
            .putExtra(ReadingAudioService.EXTRA_BOOK_ID, "book-b")
            .putExtra(ReadingAudioService.EXTRA_LANGUAGE, "zh")
            .putExtra(ReadingAudioService.EXTRA_VOICE_REQUEST_ID, PlaybackHub.voiceState.value.requestId)
            .putExtra(ReadingAudioService.EXTRA_VOICE_ID, chosenId)
        send(unauthorized)
        await("latest catalog") { !PlaybackHub.voiceState.value.loading }

        assertEquals("book-b", PlaybackHub.voiceState.value.bookId)
        assertNull(PlaybackHub.voiceState.value.catalog?.selectedVoiceId)
        assertNull(TtsVoicePreferences(app).selected("zh"))
        assertTrue(PlaybackHub.isAuthorizedCommand(stale))
    }

    @Test fun stopWhileAnIdleQueryIsInitializingStillStopsTheLatestServiceStart() {
        ServiceVoiceTtsShadow.holdInitialization = true
        load()
        await("initializing engine") { ServiceVoiceTtsShadow.initialization != null }
        send(PlaybackHub.command(app, ReadingAudioService.ACTION_STOP))
        drain()
        assertTrue(PlaybackHub.voiceState.value.loading)

        ServiceVoiceTtsShadow.initialization!!.onInit(TextToSpeech.SUCCESS)
        await("query after stop") { !PlaybackHub.voiceState.value.loading }

        assertEquals(startId, shadowOf(service).stopSelfId)
        assertNull(PlaybackHub.state.value.bookId)
        assertEquals(0, player.mediaItemCount)
    }

    @Test fun destructionFinishesAPendingQueryWithARetryableError() {
        ServiceVoiceTtsShadow.holdInitialization = true
        load()
        await("initializing engine") { ServiceVoiceTtsShadow.initialization != null }
        val request = PlaybackHub.voiceState.value.requestId
        destroyService()

        assertEquals(request, PlaybackHub.voiceState.value.requestId)
        assertFalse(PlaybackHub.voiceState.value.loading)
        assertFalse(PlaybackHub.voiceState.value.switching)
        assertNotNull(PlaybackHub.voiceState.value.error)
    }

    @Test fun anAudioFailureCancelsPendingCatalogInitializationWithoutLeavingThePickerBusy() {
        ServiceVoiceTtsShadow.holdInitialization = true
        load()
        await("initializing engine") { ServiceVoiceTtsShadow.initialization != null }
        val request = PlaybackHub.voiceState.value.requestId

        ReflectionHelpers.callInstanceMethod<Unit>(service, "reportFailure",
            ReflectionHelpers.ClassParameter.from(Exception::class.java, AudioFailure("测试故障")))
        await("interrupted catalog") { !PlaybackHub.voiceState.value.loading }

        assertEquals(request, PlaybackHub.voiceState.value.requestId)
        assertFalse(PlaybackHub.voiceState.value.switching)
        assertEquals(app.getString(R.string.audio_voice_request_interrupted), PlaybackHub.voiceState.value.error)
        assertEquals("测试故障", PlaybackHub.state.value.error)
        assertTrue(PlaybackHub.serviceAlive)
    }

    @Test fun stopInterruptsHeldVoiceSynthesisAndLateCallbacksCannotRestoreItsQueue() {
        activeFixture(playing = false)
        ServiceVoiceTtsShadow.holdSynthesis = true
        select(chosenId)
        await("held selected sentence") { ServiceVoiceTtsShadow.calls.size == 1 }
        val request = PlaybackHub.voiceState.value.requestId

        send(PlaybackHub.command(app, ReadingAudioService.ACTION_STOP))
        await("stopped voice switch") { PlaybackHub.state.value.bookId == null }
        val stoppedVoiceState = PlaybackHub.voiceState.value
        assertEquals(request, stoppedVoiceState.requestId)
        assertFalse(stoppedVoiceState.switching)
        assertNotNull(stoppedVoiceState.error)

        ServiceVoiceTtsShadow.completePending()
        drain()
        assertEquals(stoppedVoiceState, PlaybackHub.voiceState.value)
        assertNull(PlaybackHub.state.value.bookId)
        assertFalse(PlaybackHub.state.value.isPreparing)
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    private fun activeFixture(playing: Boolean) {
        val sentences = (0..3).map { SpeechSentence(it, "句子$it。", "{}", 0, "本章") }
        val fixture = SpeechBook(BookRecord("book-a", "测试书籍", "作者", "zh", 0, sentences.size), sentences)
        ReflectionHelpers.setField(service, "book", fixture)
        ReflectionHelpers.setField(service, "chapterProgressIndex", ChapterProgressIndex(sentences))
        ReflectionHelpers.setField(service, "transitionToken", -1L)
        val old = File(app.cacheDir, "old.wav").apply { writeBytes(ServiceVoiceTtsShadow.wav(10_000)) }
        val clips: MutableMap<Int, SynthesizedSentence> = ReflectionHelpers.getField(service, "clips")
        clips[1] = SynthesizedSentence(old, 10_000)
        clips[2] = SynthesizedSentence(old, 10_000)
        player.setMediaItems(listOf(1, 2).map {
            MediaItem.Builder().setMediaId(it.toString()).setUri(Uri.fromFile(old)).build()
        }, 0, 5_000)
        player.setPlaybackSpeed(1.25f)
        player.playWhenReady = playing
        drain()
        ReflectionHelpers.setField(service, "transitionToken", null)
        PlaybackHub.publish(PlaybackSnapshot(bookId = "book-a", bookTitle = "测试书籍", sentenceIndex = 1,
            sentenceCount = 4, text = "句子1。", positionMs = 5_000, durationMs = 10_000,
            speed = 1.25f, playbackSessionId = PlaybackHub.newPlaybackSessionId()))
    }

    private fun load(bookId: String = "book-a") {
        PlaybackHub.loadVoices(context, bookId, "zh")
        send(context.started!!)
    }

    private fun select(voiceId: String?, bookId: String = "book-a") {
        PlaybackHub.selectVoice(context, bookId, "zh", voiceId)
        send(context.started!!)
    }

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

    private fun destroyService() {
        ReflectionHelpers.setField(service, "book", null)
        controller.destroy()
        destroyed = true
        scheduler.runCurrent()
    }

    private class VoiceCommandContext(base: Context) : ContextWrapper(base) {
        var started: Intent? = null
        var foregroundStarts = 0
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName {
            started = service
            return ComponentName(this, ReadingAudioService::class.java)
        }
        override fun startForegroundService(service: Intent): ComponentName {
            foregroundStarts += 1
            return startService(service)
        }
    }
}

/** Different WAV durations make the voice-switch sentence-position assertion meaningful. */
@Implements(TextToSpeech::class)
class ServiceVoiceTtsShadow : ShadowTextToSpeech() {
    @Implementation override fun initTts(): Int {
        initialization = onInitListener
        if (!holdInitialization) onInitListener.onInit(TextToSpeech.SUCCESS)
        return TextToSpeech.SUCCESS
    }
    @Implementation fun getDefaultEngine(): String = "local.engine"
    @Implementation override fun getVoices(): Set<Voice> = setOf(
        Voice("automatic", Locale.CHINA, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()),
        Voice("chosen", Locale.CHINA, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()),
    )
    @Implementation fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS
    @Implementation override fun synthesizeToFile(text: CharSequence, params: Bundle?, file: File, id: String): Int {
        calls.add(SynthesisCall(currentVoice.name, text.toString()))
        file.writeBytes(wav(if (currentVoice.name == "chosen") 20_000 else 10_000))
        if (holdSynthesis) pending.add(utteranceProgressListener to id) else utteranceProgressListener.onDone(id)
        return TextToSpeech.SUCCESS
    }

    companion object {
        data class SynthesisCall(val voice: String, val text: String)
        var holdSynthesis = false
        var holdInitialization = false
        var initialization: TextToSpeech.OnInitListener? = null
        val calls = mutableListOf<SynthesisCall>()
        private val pending = mutableListOf<Pair<UtteranceProgressListener, String>>()
        fun resetFixture() {
            holdSynthesis = false
            holdInitialization = false
            initialization = null
            calls.clear()
            pending.clear()
        }
        fun completePending() {
            val completions = pending.toList()
            pending.clear()
            completions.forEach { (listener, id) -> listener.onDone(id) }
        }
        fun wav(durationMs: Int): ByteArray {
            val byteCount = durationMs * 16
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeBytes("RIFF")
                output.writeInt(Integer.reverseBytes(36 + byteCount))
                output.writeBytes("WAVEfmt ")
                output.writeInt(Integer.reverseBytes(16))
                output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                output.writeInt(Integer.reverseBytes(8_000))
                output.writeInt(Integer.reverseBytes(16_000))
                output.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
                output.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
                output.writeBytes("data")
                output.writeInt(Integer.reverseBytes(byteCount))
                output.write(ByteArray(byteCount))
            }
            return buffer.toByteArray()
        }
    }
}
