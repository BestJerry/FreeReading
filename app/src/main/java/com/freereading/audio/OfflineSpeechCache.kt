package com.freereading.audio

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.freereading.R
import com.freereading.data.SpeechSentence
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class AudioFailure(val userMessage: String, val needsVoiceData: Boolean = false) :
    Exception(userMessage)

internal data class SynthesizedSentence(val file: File, val durationMs: Long)

/** Only this class touches TTS. A mutex bounds synthesis to one request and one WAV buffer. */
internal class OfflineSpeechCache(private val context: Context) {
    private val root = File(context.cacheDir, "speech")
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null
    private var initialized: CompletableDeferred<Int>? = null
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var cleaned = false
    @Volatile private var protectedPaths: Set<String> = emptySet()
    @Volatile private var closed = false

    fun protect(files: Collection<File>) { protectedPaths = files.map { it.absolutePath }.toSet() }

    suspend fun ensure(bookId: String, sentence: SpeechSentence, language: String): SynthesizedSentence =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) throw CancellationException("Speech cache closed")
                if (!cleaned) {
                    root.walkTopDown().filter { it.isFile && it.extension == "tmp" }.forEach { it.delete() }
                    cleaned = true
                }
                require(bookId.matches(Regex("[A-Za-z0-9_-]+")))
                val voiceKey = selectOfflineVoice(language)
                val key = hash("wav-v1|$voiceKey|${sentence.text}")
                val directory = File(root, bookId)
                if (!directory.isDirectory && !directory.mkdirs()) cacheFailure()
                val file = File(directory, "$key.wav")
                if (file.isFile) {
                    try {
                        val duration = WavDuration.readMs(file)
                        file.setLastModified(System.currentTimeMillis())
                        return@withLock SynthesizedSentence(file, duration)
                    } catch (_: IOException) { file.delete() }
                }
                val pending = File(directory, "$key.tmp")
                try {
                    synthesize(sentence.text, pending)
                    currentCoroutineContext().ensureActive()
                    if (closed) throw CancellationException("Speech cache closed")
                    val duration = WavDuration.readMs(pending)
                    if (pending.length() > MAX_CACHE_BYTES || !pending.renameTo(file)) cacheFailure()
                    trim(file)
                    SynthesizedSentence(file, duration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AudioFailure) {
                    throw failure
                } catch (_: IOException) {
                    cacheFailure()
                } finally {
                    pending.delete()
                }
            }
        }

    private suspend fun selectOfflineVoice(language: String): String = withContext(Dispatchers.Main.immediate) {
        if (closed) throw CancellationException("Speech cache closed")
        if (engine == null) {
            val ready = CompletableDeferred<Int>()
            initialized = ready
            engine = TextToSpeech(context) { status -> ready.complete(status) }
        }
        val speech = engine ?: throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
        val status = try { withTimeout(INIT_TIMEOUT_MS) { initialized!!.await() } }
        catch (_: TimeoutCancellationException) {
            resetEngine()
            throw AudioFailure(context.getString(R.string.audio_timeout), true)
        }
        if (status != TextToSpeech.SUCCESS) {
            resetEngine()
            throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
        }
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) requests.remove(utteranceId)?.complete(Unit)
                }
                @Deprecated("Required abstract callback")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = fail(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId != null) requests.remove(utteranceId)
                        ?.completeExceptionally(CancellationException("Synthesis stopped"))
                }
                private fun fail(id: String?) {
                    if (id != null) requests.remove(id)?.completeExceptionally(
                        AudioFailure(context.getString(R.string.audio_tts_failed)))
                }
        })
        val locale = SpeechLanguage.requestedLocale(language, Locale.getDefault())
        val voice = speech.voices.orEmpty().asSequence()
            .filter { SpeechLanguage.sameLanguage(it.locale, locale) }
            .filter { !it.isNetworkConnectionRequired }
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { SpeechLanguage.preference(it.locale, locale) }
                .thenByDescending { it.quality }.thenBy { it.name })
            .firstOrNull()
            ?: throw AudioFailure(context.getString(R.string.audio_voice_missing,
                locale.getDisplayLanguage(Locale.getDefault())), true)
        if (speech.setVoice(voice) != TextToSpeech.SUCCESS ||
            speech.setSpeechRate(1f) != TextToSpeech.SUCCESS) {
            throw AudioFailure(context.getString(R.string.audio_tts_failed), true)
        }
        val engineName = speech.defaultEngine.orEmpty()
        val version = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(engineName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
            else { @Suppress("DEPRECATION") info.versionCode.toString() }
        }.getOrDefault("")
        "$engineName|$version|${voice.name}|${voice.locale.toLanguageTag()}|rate=1"
    }

    private suspend fun synthesize(text: String, file: File) = withContext(Dispatchers.Main.immediate) {
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            throw AudioFailure(context.getString(R.string.audio_sentence_too_long))
        }
        val tts = engine ?: throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        requests[id] = completion
        try {
            if (tts.synthesizeToFile(text, android.os.Bundle(), file, id) != TextToSpeech.SUCCESS) {
                throw AudioFailure(context.getString(R.string.audio_tts_failed))
            }
            try { withTimeout(SYNTHESIS_TIMEOUT_MS) { completion.await() } }
            catch (_: TimeoutCancellationException) {
                throw AudioFailure(context.getString(R.string.audio_timeout))
            }
        } finally {
            // Keep cancellation cleanup on the same dispatcher before releasing the mutex;
            // stopping an old request must never interrupt a newly queued request.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (requests.remove(id) != null) tts.stop()
            }
        }
    }

    private fun trim(keep: File) {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "wav" }.toList()
        var size = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (size <= MAX_CACHE_BYTES) break
            if (file == keep || file.absolutePath in protectedPaths) continue
            val length = file.length()
            if (file.delete()) size -= length
        }
        if (size > MAX_CACHE_BYTES) {
            keep.delete()
            cacheFailure()
        }
    }

    /** Called on the main thread; old callbacks retain only their own cancelled request IDs. */
    fun resetEngine() {
        requests.values.forEach { it.cancel() }
        requests.clear()
        initialized?.cancel()
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialized = null
    }
    fun close() { closed = true; resetEngine() }
    private fun cacheFailure(): Nothing = throw AudioFailure(context.getString(R.string.audio_cache_failed))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object {
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val INIT_TIMEOUT_MS = 15_000L
        const val SYNTHESIS_TIMEOUT_MS = 60_000L
    }
}
