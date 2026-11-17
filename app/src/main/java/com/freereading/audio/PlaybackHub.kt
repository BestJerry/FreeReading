package com.freereading.audio

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.freereading.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 起播失败时保留的目标与请求标识，供阅读页识别失败请求并在原位置重试。 */
data class PlaybackStartFailure(
    val requestId: String,
    val bookId: String,
    val sentenceIndex: Int,
    val positionMs: Long,
)

/** 播放服务对外发布的不可变快照，统一承载句子定位、章节正文进度及播放控制状态。 */
data class PlaybackSnapshot(
    val bookId: String? = null,
    val bookTitle: String = "",
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val chapterTitle: String = "",
    val text: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val chapterProgress: Float = 0f,
    val chapterSentenceCount: Int = 0,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    val needsVoiceData: Boolean = false,
    // 服务接受起播请求时设置新的会话 ID，自然换句沿用；结束会话时清空。
    val playbackSessionId: String? = null,
    val startFailure: PlaybackStartFailure? = null,
    val canGoToPreviousChapter: Boolean = false,
    val canGoToNextChapter: Boolean = false,
)

/** 进程内共享的播放状态与命令入口；Activity 不持有播放器，也不管理 TTS 生命周期。 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PlaybackHub {
    // 导出的媒体服务还接收系统媒体 Intent。应用私有命令携带仅存内存的令牌，
    // 使其他应用无法伪造 startService 控制命令。
    private val commandToken = UUID.randomUUID().toString()
    private const val EXTRA_COMMAND_TOKEN = "com.freereading.audio.PRIVATE_COMMAND_TOKEN"
    // 请求标识需要体现顺序，使用独立命名空间，避免泄露私有命令令牌。
    private val playbackSessionNamespace = UUID.randomUUID().toString()
    private val playbackSessionSequence = AtomicLong()
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
    private val mutableVoiceState = MutableStateFlow(TtsVoiceUiState())
    internal val voiceState: StateFlow<TtsVoiceUiState> = mutableVoiceState.asStateFlow()
    @Volatile internal var serviceAlive = false

    /** 启动前台播放服务；index 为 -1 时恢复已保存的句子及句内位置，启动失败则发布可重试目标。 */
    fun playBook(context: Context, bookId: String, index: Int, offsetMs: Long = 0,
                 playbackSessionId: String = newPlaybackSessionId()): Boolean {
        val intent = command(context, ReadingAudioService.ACTION_PLAY_BOOK)
            .putExtra(ReadingAudioService.EXTRA_BOOK_ID, bookId)
            .putExtra(ReadingAudioService.EXTRA_INDEX, index)
            .putExtra(ReadingAudioService.EXTRA_POSITION, offsetMs.coerceAtLeast(0))
            .putExtra(ReadingAudioService.EXTRA_PLAYBACK_SESSION_ID, playbackSessionId)
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: RuntimeException) {
            publish(state.value.copy(isPreparing = false, isPlaying = false,
                error = context.getString(R.string.audio_start_failed),
                startFailure = PlaybackStartFailure(playbackSessionId, bookId, index, offsetMs.coerceAtLeast(0))))
            false
        }
    }

    /** 生成进程内有序起播标识，用于确认服务接受的是当前或更新的起播请求。 */
    internal fun newPlaybackSessionId(): String =
        "$playbackSessionNamespace:${playbackSessionSequence.incrementAndGet()}"

    internal fun isCurrentPlaybackSessionId(id: String?): Boolean = playbackSessionSequenceOf(id) != null

    /** 只有同一命名空间内不早于待确认请求的会话，才能解除阅读页的起播等待。 */
    internal fun isPlaybackStartConfirmed(requestId: String?, currentSessionId: String?): Boolean {
        val requested = playbackSessionSequenceOf(requestId) ?: return false
        val current = playbackSessionSequenceOf(currentSessionId) ?: return false
        return current >= requested
    }

    private fun playbackSessionSequenceOf(id: String?): Long? {
        val prefix = "$playbackSessionNamespace:"
        if (id == null || !id.startsWith(prefix)) return null
        val suffix = id.removePrefix(prefix)
        val sequence = suffix.toLongOrNull() ?: return null
        return sequence.takeIf {
            it > 0 && it <= playbackSessionSequence.get() && it.toString() == suffix
        }
    }

    fun toggle(context: Context) = send(context, ReadingAudioService.ACTION_TOGGLE)
    fun seekBy(context: Context, deltaMs: Long) = send(context,
        ReadingAudioService.ACTION_SEEK_BY, ReadingAudioService.EXTRA_DELTA, deltaMs)
    /** 校验并保存倍速；服务尚未启动时也更新界面，后续创建播放器时恢复该偏好。 */
    fun setSpeed(context: Context, speed: Float) {
        if (!speed.isFinite()) return
        val value = speed.coerceIn(0.5f, 2f)
        persistSpeed(context, value)
        if (!serviceAlive) {
            publish(state.value.copy(speed = value))
            return
        }
        sendIntent(context, command(context, ReadingAudioService.ACTION_SPEED)
            .putExtra(ReadingAudioService.EXTRA_SPEED, value))
    }
    fun next(context: Context) = send(context, ReadingAudioService.ACTION_NEXT)
    fun previous(context: Context) = send(context, ReadingAudioService.ACTION_PREVIOUS)
    /** 章节命令限定当前书籍，避免旧页面操作另一本文本的播放会话。 */
    fun previousChapter(context: Context, bookId: String) = sendChapter(context,
        ReadingAudioService.ACTION_PREVIOUS_CHAPTER, bookId)
    /** 从当前书籍下一章的首句开播，具体边界由播放服务校验。 */
    fun nextChapter(context: Context, bookId: String) = sendChapter(context,
        ReadingAudioService.ACTION_NEXT_CHAPTER, bookId)
    fun stop(context: Context) = send(context, ReadingAudioService.ACTION_STOP)

    internal fun loadVoices(context: Context, bookId: String, language: String) =
        sendVoiceRequest(context, bookId, language, switching = false)

    internal fun selectVoice(context: Context, bookId: String, language: String, voiceId: String?) =
        sendVoiceRequest(context, bookId, language, switching = true, voiceId = voiceId)

    /** 先发布带新标识的界面状态再发送命令，异步返回时可丢弃被后续操作替代的结果。 */
    private fun sendVoiceRequest(context: Context, bookId: String, language: String,
                                 switching: Boolean, voiceId: String? = null) {
        val requestId = UUID.randomUUID().toString()
        val old = voiceState.value
        publishVoices(TtsVoiceUiState(bookId = bookId, language = language, requestId = requestId,
            loading = !switching, switching = switching,
            catalog = old.catalog.takeIf { old.bookId == bookId && old.language == language }))
        val action = if (switching) ReadingAudioService.ACTION_SELECT_VOICE else ReadingAudioService.ACTION_LOAD_VOICES
        val intent = command(context, action).putExtra(ReadingAudioService.EXTRA_BOOK_ID, bookId)
            .putExtra(ReadingAudioService.EXTRA_LANGUAGE, language)
            .putExtra(ReadingAudioService.EXTRA_VOICE_REQUEST_ID, requestId)
            .putExtra(ReadingAudioService.EXTRA_VOICE_ID, voiceId)
        try { context.applicationContext.startService(intent) }
        catch (_: RuntimeException) {
            if (voiceState.value.requestId == requestId) publishVoices(voiceState.value.copy(
                loading = false, switching = false, error = context.getString(R.string.audio_start_failed)))
        }
    }

    /** 优先打开系统 TTS 设置；厂商未提供专用页面时回退到系统设置首页。 */
    fun openVoiceSettings(context: Context) {
        val candidates = listOf(Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS))
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: RuntimeException) { /* 部分厂商没有独立的 TTS 设置页面，继续尝试后备入口。 */ }
        }
    }

    internal fun publish(snapshot: PlaybackSnapshot) { mutableState.value = snapshot }
    internal fun publishVoices(snapshot: TtsVoiceUiState) { mutableVoiceState.value = snapshot }
    /** 读取持久化倍速，并兼容无效旧值或超出当前支持范围的值。 */
    internal fun savedSpeed(context: Context): Float {
        val speed = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE).getFloat("speed", 1f)
        return if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f
    }
    internal fun persistSpeed(context: Context, speed: Float) {
        context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE).edit()
            .putFloat("speed", speed).apply()
    }
    /** 构造带进程内令牌的应用私有命令；系统媒体会话走另一条可信控制入口。 */
    internal fun command(context: Context, action: String) =
        Intent(context.applicationContext, ReadingAudioService::class.java).setAction(action)
            .putExtra(EXTRA_COMMAND_TOKEN, commandToken)
    internal fun isAuthorizedCommand(intent: Intent): Boolean =
        runCatching { intent.getStringExtra(EXTRA_COMMAND_TOKEN) }.getOrNull() == commandToken
    private fun send(context: Context, action: String, key: String? = null, value: Long = 0) {
        if (!serviceAlive) return
        val intent = command(context, action)
        if (key != null) intent.putExtra(key, value)
        sendIntent(context, intent)
    }
    /** 仅向存活且属于该书的会话发送章节命令，不为失效页面单独启动服务。 */
    private fun sendChapter(context: Context, action: String, bookId: String) {
        if (!serviceAlive || state.value.bookId != bookId) return
        sendIntent(context, command(context, action).putExtra(ReadingAudioService.EXTRA_BOOK_ID, bookId))
    }
    private fun sendIntent(context: Context, intent: Intent) {
        try { context.applicationContext.startService(intent) }
        catch (_: RuntimeException) {
            publish(state.value.copy(error = context.getString(R.string.audio_start_failed)))
        }
    }
}
