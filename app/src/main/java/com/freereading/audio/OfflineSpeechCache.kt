package com.freereading.audio

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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

/** 可直接呈现给用户的听书异常；needsVoiceData 指示是否提供系统语音设置入口。 */
internal class AudioFailure(val userMessage: String, val needsVoiceData: Boolean = false) :
    Exception(userMessage)

/** 已校验的句子音频文件及真实毫秒时长，供播放器和跨句跳转共用。 */
internal data class SynthesizedSentence(val file: File, val durationMs: Long)

/** 系统 TTS 与离线音频缓存的唯一访问层；互斥锁确保同一时刻只有一个合成请求和 WAV 缓冲。 */
internal class OfflineSpeechCache(private val context: Context) {
    private val root = File(context.cacheDir, "speech")
    private val mutex = Mutex()
    private val voicePreferences = TtsVoicePreferences(context)
    private var engine: TextToSpeech? = null
    private var engineContext: DefaultEngineContext? = null
    private var configuredEnginePackage: String? = null
    private var enginePackage: String? = null
    private var initialized: CompletableDeferred<Int>? = null
    private var listenerInstalled = false
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var cleaned = false
    @Volatile private var protectedPaths: Set<String> = emptySet()
    @Volatile private var closed = false

    /** 标记播放队列仍在使用的文件，缓存回收时必须保留这些音频。 */
    fun protect(files: Collection<File>) { protectedPaths = files.map { it.absolutePath }.toSet() }

    /** 枚举默认引擎的可用离线音色，不改变正在合成的请求使用的音色。 */
    suspend fun voices(language: String): TtsVoiceCatalog = withContext(Dispatchers.IO) {
        mutex.withLock { readVoiceCatalog(language).catalog }
    }

    /** 由调用方取消合成／预取后再切换；同一互斥锁也保护音色偏好的修改。 */
    suspend fun selectVoice(language: String, voiceId: String?): TtsVoiceCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = readVoiceCatalog(language)
            require(voiceId == null || state.catalog.voices.any { it.id == voiceId }) {
                "Voice is no longer available for this language and engine"
            }
            voicePreferences.select(state.locale.language, voiceId)
            TtsVoiceSelection.catalog(state.catalog.engineId, state.voices, voiceId)
        }
    }

    /**
     * 取得指定句子的真实音频：优先复用校验通过的缓存，未命中则串行合成为临时文件后重命名。
     * 缓存键包含引擎、版本、音色和文本；finally 中尝试删除临时文件，避免将未提交文件用作音频。
     */
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

    /** 由主线程调用，初始化并验证默认引擎和回调；默认引擎变化时重新绑定，连接失败则释放并报错。 */
    private suspend fun readyEngine(): TextToSpeech {
        if (closed) throw CancellationException("Speech cache closed")
        // 服务存活期间，用户仍可在系统设置中更换默认引擎。
        // 枚举前重新初始化，不能把旧引擎的音色标成新引擎提供的音色。
        if (engine != null && engine!!.defaultEngine.orEmpty() != configuredEnginePackage) resetEngine()
        if (engine == null) {
            val ready = CompletableDeferred<Int>()
            initialized = ready
            val bindingContext = DefaultEngineContext(context)
            engineContext = bindingContext
            try {
                engine = TextToSpeech(bindingContext) { status -> ready.complete(status) }
                configuredEnginePackage = engine!!.defaultEngine.orEmpty()
                enginePackage = bindingContext.boundEnginePackage ?: configuredEnginePackage
            } catch (_: RuntimeException) {
                resetEngine()
                throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
            }
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
        if (closed || engine !== speech) throw CancellationException("Speech cache closed")
        val binding = engineContext
        if (enginePackage.isNullOrBlank() || enginePackage != configuredEnginePackage ||
            (binding?.attemptedEnginePackage != null && binding.boundEnginePackage != configuredEnginePackage)) {
            resetEngine()
            throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
        }
        if (!listenerInstalled) {
            val result = speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
            if (result != TextToSpeech.SUCCESS) {
                resetEngine()
                throw AudioFailure(context.getString(R.string.audio_tts_unavailable), true)
            }
            listenerInstalled = true
        }
        return speech
    }

    /** 一次音色查询对应的引擎、语言与目录，避免选择过程混用不同引擎的信息。 */
    private data class VoiceCatalogState(
        val speech: TextToSpeech,
        val locale: Locale,
        val voices: List<Voice>,
        val catalog: TtsVoiceCatalog,
    )

    /** 调用方须持有互斥锁；仅读取引擎，不调用 setVoice／setLanguage，失效偏好回退自动选择。 */
    private suspend fun readVoiceCatalog(language: String): VoiceCatalogState =
        withContext(Dispatchers.Main.immediate) {
            val speech = readyEngine()
            val locale = SpeechLanguage.requestedLocale(language, Locale.getDefault())
            val voices = TtsVoiceSelection.eligibleVoices(speech.voices.orEmpty(), locale)
            val saved = voicePreferences.selected(locale.language)
            val catalog = TtsVoiceSelection.catalog(enginePackage.orEmpty(), voices, saved)
            if (saved != catalog.selectedVoiceId) voicePreferences.select(locale.language, null)
            VoiceCatalogState(speech, locale, voices, catalog)
        }

    /** 设置有效离线音色并固定合成语速为 1 倍，返回包含引擎版本的缓存标识；倍速由播放器处理。 */
    private suspend fun selectOfflineVoice(language: String): String = withContext(Dispatchers.Main.immediate) {
        val state = readVoiceCatalog(language)
        val speech = state.speech
        val voice = state.voices.firstOrNull {
            TtsVoiceSelection.voiceId(state.catalog.engineId, it.name) == state.catalog.effectiveVoiceId
        } ?: throw AudioFailure(context.getString(R.string.audio_voice_missing,
            state.locale.getDisplayLanguage(Locale.getDefault())), true)
        if (speech.setVoice(voice) != TextToSpeech.SUCCESS ||
            speech.setSpeechRate(1f) != TextToSpeech.SUCCESS) {
            throw AudioFailure(context.getString(R.string.audio_tts_failed), true)
        }
        val engineName = state.catalog.engineId
        val version = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(engineName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
            else { @Suppress("DEPRECATION") info.versionCode.toString() }
        }.getOrDefault("")
        "$engineName|$version|${voice.name}|${voice.locale.toLanguageTag()}|rate=1"
    }

    /** 将系统回调转为可取消、有超时的挂起请求；请求标识保证完成回调只结束对应任务。 */
    private suspend fun synthesize(text: String, file: File) = withContext(Dispatchers.Main.immediate) {
        if (closed) throw CancellationException("Speech cache closed")
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
            // 释放互斥锁前在同一线程完成不可取消的清理，
            // 避免旧请求迟到的 stop() 打断刚开始的新请求。
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (requests.remove(id) != null) tts.stop()
            }
        }
    }

    /** 按最近使用时间回收旧 WAV，保护当前文件和队列；仍超限则尝试移除本次新文件并报告错误。 */
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

    /** 主线程调用：取消未完成请求并释放引擎；旧回调只能携带已失效的自身请求标识。 */
    fun resetEngine() {
        requests.values.forEach { it.cancel() }
        requests.clear()
        initialized?.cancel()
        engine?.stop()
        engine?.shutdown()
        engine = null
        engineContext = null
        configuredEnginePackage = null
        enginePackage = null
        initialized = null
        listenerInstalled = false
    }
    /** 永久关闭该缓存实例，阻止仍在等待互斥锁的任务重新创建引擎。 */
    fun close() { closed = true; resetEngine() }
    private fun cacheFailure(): Nothing = throw AudioFailure(context.getString(R.string.audio_cache_failed))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * 旧版 Android 在默认引擎绑定失败后可能静默改绑其他引擎，因此锁定首次尝试的默认引擎，
     * 防止后备引擎的音色和缓存被误标为默认引擎。
     * 新版 Android 的系统 TTS 会话通过 onInit(ERROR) 报告连接失败，
     * 不调用 Context.bindService，也不静默切换到其他引擎。
     */
    private class DefaultEngineContext(base: Context) : ContextWrapper(base) {
        var attemptedEnginePackage: String? = null
            private set
        var boundEnginePackage: String? = null
            private set

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (service.action != TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE) {
                return super.bindService(service, connection, flags)
            }
            val target = service.`package` ?: service.component?.packageName ?: return false
            if (attemptedEnginePackage != null && attemptedEnginePackage != target) return false
            attemptedEnginePackage = target
            return super.bindService(service, connection, flags).also { bound ->
                if (bound) boundEnginePackage = target
            }
        }
    }

    /** 缓存容量和引擎初始化／句子合成超时边界。 */
    private companion object {
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val INIT_TIMEOUT_MS = 15_000L
        const val SYNTHESIS_TIMEOUT_MS = 60_000L
    }
}
