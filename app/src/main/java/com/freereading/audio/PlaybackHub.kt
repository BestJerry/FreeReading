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
)

/** One process-wide stream; activities never own the player or TTS lifecycle. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PlaybackHub {
    // The exported MediaSessionService also accepts platform media intents. Our app-only
    // startService commands use an unpersisted capability so other apps cannot forge them.
    private val commandToken = UUID.randomUUID().toString()
    private const val EXTRA_COMMAND_TOKEN = "com.freereading.audio.PRIVATE_COMMAND_TOKEN"
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
    @Volatile internal var serviceAlive = false

    /** Pass index = -1 to restore the persisted sentence and within-sentence position. */
    fun playBook(context: Context, bookId: String, index: Int, offsetMs: Long = 0) {
        val intent = command(context, ReadingAudioService.ACTION_PLAY_BOOK)
            .putExtra(ReadingAudioService.EXTRA_BOOK_ID, bookId)
            .putExtra(ReadingAudioService.EXTRA_INDEX, index)
            .putExtra(ReadingAudioService.EXTRA_POSITION, offsetMs.coerceAtLeast(0))
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: RuntimeException) {
            publish(state.value.copy(isPreparing = false, isPlaying = false,
                error = context.getString(R.string.audio_start_failed)))
        }
    }

    fun toggle(context: Context) = send(context, ReadingAudioService.ACTION_TOGGLE)
    fun seekBy(context: Context, deltaMs: Long) = send(context,
        ReadingAudioService.ACTION_SEEK_BY, ReadingAudioService.EXTRA_DELTA, deltaMs)
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
    fun stop(context: Context) = send(context, ReadingAudioService.ACTION_STOP)

    fun openVoiceSettings(context: Context) {
        val candidates = listOf(Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS))
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: RuntimeException) { /* Some OEMs omit the dedicated TTS settings screen. */ }
        }
    }

    internal fun publish(snapshot: PlaybackSnapshot) { mutableState.value = snapshot }
    internal fun savedSpeed(context: Context): Float {
        val speed = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE).getFloat("speed", 1f)
        return if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f
    }
    internal fun persistSpeed(context: Context, speed: Float) {
        context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE).edit()
            .putFloat("speed", speed).apply()
    }
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
    private fun sendIntent(context: Context, intent: Intent) {
        try { context.applicationContext.startService(intent) }
        catch (_: RuntimeException) {
            publish(state.value.copy(error = context.getString(R.string.audio_start_failed)))
        }
    }
}
