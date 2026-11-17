package com.freereading.audio

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.freereading.ReadingApplication
import com.freereading.R
import com.freereading.data.SpeechSentence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech

/** Exercises the real cache, mutex and WAV validation using a deterministic local TTS engine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class, shadows = [VoiceCacheTtsShadow::class])
class OfflineSpeechCacheVoiceTest {
    private lateinit var context: Context
    private lateinit var cache: OfflineSpeechCache
    private val first = voice("automatic", "zh-CN", Voice.QUALITY_HIGH)
    private val second = voice("chosen", "zh-CN", Voice.QUALITY_NORMAL)
    private val sentence = SpeechSentence(0, "切换离线音色。", "{}", 0, "测试")

    @Before fun createCache() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "speech").deleteRecursively()
        VoiceCacheTtsShadow.resetFixture()
        VoiceCacheTtsShadow.availableVoices = setOf(first, second)
        installEngine("local.engine", 1)
        cache = OfflineSpeechCache(context)
    }

    @After fun closeCache() {
        cache.close()
        context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "speech").deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun listingAndSavingChoicesDoNotChangeTheEnginesCurrentVoice() = runBlocking {
        val initial = cache.voices("zho")
        val speech = ShadowTextToSpeech.getLastTextToSpeechInstance()
        speech.voice = first
        val listener = shadowOf(speech).utteranceProgressListener

        val chosen = initial.voices.single { it.name == second.name }.id
        val saved = cache.selectVoice("zh-CN", chosen)
        val reloaded = cache.voices("chi-Hant-TW")

        assertEquals("local.engine", initial.engineId)
        assertNull(initial.selectedVoiceId)
        assertEquals(chosen, saved.selectedVoiceId)
        assertEquals(chosen, reloaded.effectiveVoiceId)
        assertSame(first, speech.voice)
        assertSame(listener, shadowOf(speech).utteranceProgressListener)
    }

    @Test fun sameSentenceUsesSeparateWavFilesForChosenVoicesAndReusesEachBucket() = runBlocking {
        val automatic = cache.ensure("voice-test", sentence, "zh-CN")
        val chosenId = cache.voices("zh-CN").voices.single { it.name == second.name }.id
        cache.selectVoice("zho", chosenId)
        val manual = cache.ensure("voice-test", sentence, "chi")
        val repeated = cache.ensure("voice-test", sentence, "zh")
        cache.selectVoice("zh", null)
        val automaticAgain = cache.ensure("voice-test", sentence, "zh-CN")

        assertNotEquals(automatic.file, manual.file)
        assertEquals(manual.file, repeated.file)
        assertEquals(automatic.file, automaticAgain.file)
        assertEquals(1_000L, manual.durationMs)
        assertEquals(listOf(first.name, second.name), VoiceCacheTtsShadow.synthesizedVoices)
        assertEquals(2, File(context.cacheDir, "speech/voice-test").listFiles()!!.size)
    }

    @Test fun sameNameVoiceVariantsHaveUniqueCatalogIdsAndSynthesisMatchesTheDisplayedChoice() = runBlocking {
        VoiceCacheTtsShadow.availableVoices = setOf(
            voice(first.name, "zh-TW", Voice.QUALITY_VERY_HIGH),
            voice(first.name, "zh-CN", Voice.QUALITY_LOW),
            first,
            second,
        )
        assertEquals(4, VoiceCacheTtsShadow.availableVoices.size)

        val catalog = cache.voices("zh-CN")
        assertEquals(2, catalog.voices.size)
        assertEquals(catalog.voices.size, catalog.voices.map { it.id }.distinct().size)
        val effective = catalog.voices.single { it.id == catalog.effectiveVoiceId }
        assertEquals(first.name, effective.name)
        assertEquals(first.locale, effective.locale)
        assertEquals(first.quality, effective.quality)

        val clip = cache.ensure("voice-test", sentence, "zh-CN")
        val synthesized = ShadowTextToSpeech.getLastTextToSpeechInstance().voice
        assertEquals(effective.id, TtsVoiceSelection.voiceId(catalog.engineId, synthesized.name))
        assertEquals(effective.locale, synthesized.locale)
        assertEquals(effective.quality, synthesized.quality)
        assertEquals(listOf(effective.name), VoiceCacheTtsShadow.synthesizedVoices)
        assertTrue(clip.file.isFile)
    }

    @Test fun preferenceSurvivesCacheRecreation() = runBlocking {
        val chosenId = cache.voices("zh").voices.single { it.name == second.name }.id
        cache.selectVoice("zho", chosenId)
        cache.close()
        cache = OfflineSpeechCache(context)

        assertEquals(chosenId, cache.voices("zh-Hant-TW").selectedVoiceId)
        cache.ensure("voice-test", sentence, "zh")
        assertEquals(listOf(second.name), VoiceCacheTtsShadow.synthesizedVoices)
    }

    @Test fun removedChoiceFallsBackAndSynthesizesWithoutAMissingVoiceError() = runBlocking {
        val chosenId = cache.voices("zh").voices.single { it.name == second.name }.id
        cache.selectVoice("zh", chosenId)
        VoiceCacheTtsShadow.availableVoices = setOf(first)

        val current = cache.voices("zho")
        val clip = cache.ensure("voice-test", sentence, "zh")

        assertNull(current.selectedVoiceId)
        assertEquals(current.voices.single().id, current.effectiveVoiceId)
        assertTrue(clip.file.isFile)
        assertEquals(listOf(first.name), VoiceCacheTtsShadow.synthesizedVoices)
        assertNull(TtsVoicePreferences(context).selected("zh"))
    }

    @Test fun voiceRemovedBetweenOpeningCatalogAndSelectionIsRejectedWithoutSavingIt() = runBlocking {
        val oldId = cache.voices("zh").voices.single { it.name == second.name }.id
        VoiceCacheTtsShadow.availableVoices = setOf(first)

        val failure = runCatching { cache.selectVoice("zh", oldId) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(cache.voices("zh").selectedVoiceId)
        assertNull(TtsVoicePreferences(context).selected("zh"))
    }

    @Test fun unrelatedOrNetworkOnlyVoicesYieldAnEmptyCatalogAndOriginalSynthesisError() = runBlocking {
        VoiceCacheTtsShadow.availableVoices = setOf(
            voice("english", "en-US"),
            Voice("network", Locale.CHINA, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()),
        )

        assertTrue(cache.voices("zho").voices.isEmpty())
        val failure = runCatching { cache.ensure("voice-test", sentence, "zh") }.exceptionOrNull()

        assertTrue(failure is AudioFailure)
        assertTrue((failure as AudioFailure).needsVoiceData)
        assertTrue(VoiceCacheTtsShadow.synthesizedVoices.isEmpty())
    }

    @Test fun replacingDefaultEngineInvalidatesOldChoiceAndUsesANewCacheBucket() = runBlocking {
        val chosenId = cache.voices("zh").voices.single { it.name == second.name }.id
        cache.selectVoice("zh", chosenId)
        val old = cache.ensure("voice-test", sentence, "zh")
        val previousEngine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        VoiceCacheTtsShadow.defaultEngineId = "replacement.engine"
        installEngine("replacement.engine", 1)

        val catalog = cache.voices("zh")
        val replaced = cache.ensure("voice-test", sentence, "zh")

        assertTrue(shadowOf(previousEngine).isShutdown)
        assertEquals("replacement.engine", catalog.engineId)
        assertNull(catalog.selectedVoiceId)
        assertNotEquals(old.file, replaced.file)
        assertEquals(listOf(second.name, first.name), VoiceCacheTtsShadow.synthesizedVoices)
    }

    @Test fun failedDefaultBindCannotSilentlyUseAnotherEngineAndMislabelItsVoices() = runBlocking {
        val bindingContext = RecordingBindingContext(context, defaultSucceeds = false)
        cache.close()
        cache = OfflineSpeechCache(bindingContext)
        VoiceCacheTtsShadow.tryBindingInInit = true

        val failure = runCatching { cache.voices("zh") }.exceptionOrNull()

        assertTrue(failure is AudioFailure)
        assertEquals(context.getString(R.string.audio_tts_unavailable), (failure as AudioFailure).userMessage)
        assertTrue(failure.needsVoiceData)
        // The fixture's fallback engine would succeed, but the cache never lets it bind.
        assertEquals(listOf("local.engine"), bindingContext.boundPackages)
        assertTrue(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isShutdown)
        assertTrue(VoiceCacheTtsShadow.synthesizedVoices.isEmpty())
    }

    @Test fun successfulDirectBindUsesTheDefaultPackagesIdentity() = runBlocking {
        val bindingContext = RecordingBindingContext(context, defaultSucceeds = true)
        cache.close()
        cache = OfflineSpeechCache(bindingContext)
        VoiceCacheTtsShadow.tryBindingInInit = true

        val catalog = cache.voices("zh")
        cache.ensure("voice-test", sentence, "zh")

        assertEquals("local.engine", catalog.engineId)
        assertEquals(listOf("local.engine"), bindingContext.boundPackages)
        assertTrue(catalog.voices.all { it.id == TtsVoiceSelection.voiceId("local.engine", it.name) })
    }

    @Test fun initializationExceptionUsesTheOriginalTtsErrorAndAllowsALaterRetry() = runBlocking {
        VoiceCacheTtsShadow.failInitialization = true

        val failure = runCatching { cache.voices("zh") }.exceptionOrNull()

        assertTrue(failure is AudioFailure)
        assertEquals(context.getString(R.string.audio_tts_unavailable), (failure as AudioFailure).userMessage)
        assertTrue(failure.needsVoiceData)
        VoiceCacheTtsShadow.failInitialization = false
        assertFalse(cache.voices("zh").voices.isEmpty())
    }

    @Test fun engineVersionAndVoiceLocaleEachCreateNewCacheBuckets() = runBlocking {
        VoiceCacheTtsShadow.availableVoices = setOf(first)
        val original = cache.ensure("voice-test", sentence, "zh")
        installEngine("local.engine", 2)
        val updated = cache.ensure("voice-test", sentence, "zh")
        VoiceCacheTtsShadow.availableVoices = setOf(voice(first.name, "zh-TW", first.quality))
        val localeChanged = cache.ensure("voice-test", sentence, "zh")

        assertNotEquals(original.file, updated.file)
        assertNotEquals(updated.file, localeChanged.file)
        assertEquals(3, VoiceCacheTtsShadow.synthesizedVoices.size)
    }

    @Test fun cancellingPrefetchReleasesTheMutexAndNewSynthesisUsesTheChosenVoice() = runBlocking {
        val chosenId = cache.voices("zh").voices.single { it.name == second.name }.id
        VoiceCacheTtsShadow.completeSynthesis = false
        val pending = async { cache.ensure("voice-test", sentence, "zh") }
        withTimeout(3_000) { VoiceCacheTtsShadow.synthesisStarted.await() }
        val listing = async { cache.voices("zh") }
        yield()
        assertFalse(listing.isCompleted)

        pending.cancelAndJoin()
        withTimeout(3_000) { listing.await() }
        assertTrue(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isStopped)
        cache.selectVoice("zh", chosenId)
        VoiceCacheTtsShadow.completeSynthesis = true
        val clip = cache.ensure("voice-test", sentence, "zh")

        assertEquals(listOf(first.name, second.name), VoiceCacheTtsShadow.synthesizedVoices)
        assertTrue(clip.file.isFile)
        assertFalse(File(context.cacheDir, "speech").walkTopDown().any { it.extension == "tmp" })
    }

    @Test fun closeCancelsPendingSynthesisAndNeverReinitializesAnEngine() = runBlocking {
        VoiceCacheTtsShadow.completeSynthesis = false
        val pending = async { cache.ensure("voice-test", sentence, "zh") }
        withTimeout(3_000) { VoiceCacheTtsShadow.synthesisStarted.await() }
        val speech = ShadowTextToSpeech.getLastTextToSpeechInstance()

        cache.close()
        withTimeout(3_000) { pending.join() }
        val failure = runCatching { cache.voices("zh") }.exceptionOrNull()

        assertTrue(pending.isCancelled)
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(shadowOf(speech).isShutdown)
        assertSame(speech, ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertFalse(File(context.cacheDir, "speech").walkTopDown().any { it.extension == "tmp" })
    }

    private fun installEngine(name: String, version: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            @Suppress("DEPRECATION")
            versionCode = version
        })
    }

    private fun voice(name: String, tag: String, quality: Int = Voice.QUALITY_NORMAL) =
        Voice(name, Locale.forLanguageTag(tag), quality, Voice.LATENCY_NORMAL, false, emptySet())

    private class RecordingBindingContext(base: Context, private val defaultSucceeds: Boolean) : ContextWrapper(base) {
        val boundPackages = mutableListOf<String>()
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val target = service.`package`.orEmpty()
            boundPackages.add(target)
            return target != "local.engine" || defaultSucceeds
        }
    }
}

/** Retains Robolectric's TTS lifecycle and setVoice tracking; emits a valid, silent PCM WAV. */
@Implements(TextToSpeech::class)
class VoiceCacheTtsShadow : ShadowTextToSpeech() {
    @Implementation
    override fun initTts(): Int {
        if (failInitialization) throw IllegalStateException("Engine service failed during construction")
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val result = if (!tryBindingInInit ||
            context.bindService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .setPackage(defaultEngineId), connection, Context.BIND_AUTO_CREATE) ||
            context.bindService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .setPackage("fallback.engine"), connection, Context.BIND_AUTO_CREATE)) TextToSpeech.SUCCESS
            else TextToSpeech.ERROR
        onInitListener.onInit(result)
        return result
    }

    @Implementation
    fun getDefaultEngine(): String = defaultEngineId

    @Implementation
    override fun getVoices(): Set<Voice> = availableVoices

    @Implementation
    fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS

    @Implementation
    override fun synthesizeToFile(text: CharSequence, params: Bundle?, file: File, id: String): Int {
        synthesizedVoices.add(currentVoice.name)
        file.writeBytes(silentWav())
        synthesisStarted.complete(Unit)
        if (completeSynthesis) utteranceProgressListener.onDone(id)
        return TextToSpeech.SUCCESS
    }

    private fun silentWav(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + 16_000))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(8_000))
            output.writeInt(Integer.reverseBytes(16_000))
            output.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(16_000))
            output.write(ByteArray(16_000))
        }
        return buffer.toByteArray()
    }

    companion object {
        @Volatile var defaultEngineId = "local.engine"
        @Volatile var availableVoices: Set<Voice> = emptySet()
        @Volatile var completeSynthesis = true
        @Volatile var tryBindingInInit = false
        @Volatile var failInitialization = false
        var synthesisStarted = CompletableDeferred<Unit>()
        val synthesizedVoices = mutableListOf<String>()

        fun resetFixture() {
            defaultEngineId = "local.engine"
            availableVoices = emptySet()
            completeSynthesis = true
            tryBindingInInit = false
            failInitialization = false
            synthesisStarted = CompletableDeferred()
            synthesizedVoices.clear()
        }
    }
}
