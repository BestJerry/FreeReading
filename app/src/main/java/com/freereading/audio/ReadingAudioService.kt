package com.freereading.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.freereading.R
import com.freereading.ReadingApplication
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@UnstableApi
class ReadingAudioService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as ReadingApplication).repository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var speech: OfflineSpeechCache
    private var book: SpeechBook? = null
    private var chapterProgressIndex: ChapterProgressIndex? = null
    private var generation = 0L
    private var transitionToken: Long? = null
    private var operation: Job? = null
    private var prefetch: Job? = null
    private val clips = mutableMapOf<Int, SynthesizedSentence>()
    private val relativeSeeks = RelativeSeekAccumulator()
    private val persistenceMutex = Mutex()
    private val saveSequence = AtomicLong()
    private val latestSave = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        speech = OfflineSpeechCache(applicationContext)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        player.setPlaybackSpeed(PlaybackHub.savedSpeed(this))
        PlaybackHub.publish(PlaybackHub.state.value.copy(speed = player.playbackParameters.speed))
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                PlaybackHub.persistSpeed(this@ReadingAudioService, playbackParameters.speed.coerceIn(0.5f, 2f))
                PlaybackHub.publish(PlaybackHub.state.value.copy(speed = playbackParameters.speed))
            }
            override fun onEvents(player: Player, events: Player.Events) {
                refreshSnapshot()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (transitionToken != null) return
                refreshSnapshot()
                saveCurrentLater()
                prunePlayedQueue()
                startPrefetch()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || transitionToken != null) return
                val currentBook = book ?: return
                val index = currentIndex()
                if (index < currentBook.sentences.lastIndex && player.playWhenReady) {
                    moveTo(index + 1, 0)
                } else {
                    refreshSnapshot()
                    saveCurrentLater()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                reportFailure(AudioFailure(getString(R.string.audio_playback_failed)))
            }
        })
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun seekBack() = seekBy(-SEEK_MS)
            override fun seekForward() = seekBy(SEEK_MS)
            override fun getSeekBackIncrement(): Long = SEEK_MS
            override fun getSeekForwardIncrement(): Long = SEEK_MS
            override fun seekToNext() = moveRelative(1)
            override fun seekToNextMediaItem() = moveRelative(1)
            override fun seekToPrevious() = moveRelative(-1)
            override fun seekToPreviousMediaItem() = moveRelative(-1)
            override fun play() = requestPlayback(true)
            override fun pause() = requestPlayback(false)
            override fun setPlayWhenReady(playWhenReady: Boolean) = requestPlayback(playWhenReady)
            override fun stop() = stopPlayback()
        }
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
            .setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                    .setDisplayName(getString(R.string.audio_backward))
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setSlots(CommandButton.SLOT_BACK).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
                    .setDisplayName(getString(R.string.audio_forward))
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setSlots(CommandButton.SLOT_FORWARD).build(),
            ))
        launchPendingIntent()?.let { sessionBuilder.setSessionActivity(it) }
        session = sessionBuilder.build()
        addSession(session)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL_ID)
            .setChannelName(R.string.audio_channel).build())
        PlaybackHub.serviceAlive = true
        serviceScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                refreshSnapshot()
                ticks += 1
                if (ticks % 5 == 0 && player.isPlaying) saveCurrentLater()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.isTrusted || controllerInfo.packageName == packageName) session else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action != null && action in PRIVATE_ACTIONS && !PlaybackHub.isAuthorizedCommand(intent)) {
                // Reject app-only commands without disturbing an existing playback session.
                if (book == null && transitionToken == null && player.mediaItemCount == 0) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_PLAY_BOOK -> {
                // startForegroundService's deadline includes EPUB parsing and TTS initialization.
                // Fulfil it before launching either operation.
                try { showPreparingNotification() }
                catch (_: RuntimeException) {
                    PlaybackHub.publish(PlaybackHub.state.value.copy(isPreparing = false,
                        isPlaying = false, error = getString(R.string.audio_start_failed)))
                    stopSelf()
                    return START_NOT_STICKY
                }
                val id = intent.getStringExtra(EXTRA_BOOK_ID)
                if (id.isNullOrBlank()) { stopPlayback(); return START_NOT_STICKY }
                playBook(id, intent.getIntExtra(EXTRA_INDEX, -1),
                    intent.getLongExtra(EXTRA_POSITION, 0))
            }
            ACTION_TOGGLE -> requestPlayback(if (player.playbackState == Player.STATE_ENDED)
                true else !player.playWhenReady)
            ACTION_SEEK_BY -> seekBy(intent.getLongExtra(EXTRA_DELTA, 0))
            ACTION_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1f)
                if (speed.isFinite()) player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
                refreshSnapshot()
            }
            ACTION_NEXT -> moveRelative(1)
            ACTION_PREVIOUS -> moveRelative(-1)
            ACTION_STOP -> stopPlayback()
            else -> return super.onStartCommand(intent, flags, startId)
        }
        return START_NOT_STICKY
    }

    private fun playBook(id: String, index: Int, offsetMs: Long) {
        relativeSeeks.clear()
        val previousProgress = captureProgress()
        book = null
        PlaybackHub.publish(PlaybackSnapshot(bookId = id,
            bookTitle = repository.books.value.firstOrNull { it.id == id }?.title.orEmpty(),
            sentenceIndex = index.coerceAtLeast(0), speed = player.playbackParameters.speed))
        player.playWhenReady = true
        beginTransition { token ->
            previousProgress?.let { persist(it) }
            val loaded = withContext(Dispatchers.IO) { repository.loadSpeechBook(id) }
            if (loaded.sentences.isEmpty()) throw AudioFailure(getString(R.string.audio_empty_book))
            val saved = if (index < 0) withContext(Dispatchers.IO) { repository.progress(id) } else null
            ensureActiveGeneration(token)
            val progressIndex = withContext(Dispatchers.IO) { ChapterProgressIndex(loaded.sentences) }
            ensureActiveGeneration(token)
            book = loaded
            chapterProgressIndex = progressIndex
            clips.clear()
            val target = (saved?.sentenceIndex ?: index).coerceIn(0, loaded.sentences.lastIndex)
            val position = (saved?.audioOffsetMs ?: offsetMs).coerceAtLeast(0)
            publishPreparing(loaded, target, position)
            openSentence(loaded, target, position, token)
        }
    }

    private fun moveTo(index: Int, positionMs: Long) {
        val currentBook = book ?: return
        relativeSeeks.clear()
        val progress = captureProgress()
        val target = index.coerceIn(0, currentBook.sentences.lastIndex)
        // Publish absolute navigation before any persistence/synthesis suspension, so the
        // next button press advances from this requested sentence rather than the old one.
        publishPreparing(currentBook, target, positionMs)
        beginTransition { token ->
            progress?.let { persist(it) }
            openSentence(currentBook, target, positionMs, token)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val currentBook = book ?: return
        val start = currentIndex()
        val position = if (transitionToken != null) PlaybackHub.state.value.positionMs
            else player.currentPosition.coerceAtLeast(0)
        val request = relativeSeeks.enqueue(SentencePosition(start, position), deltaMs)
        beginTransition { token ->
            val target = SentenceTimeline.seek(currentBook.sentences.size,
                request.anchor.index, request.anchor.positionMs, request.deltaMs) { index ->
                val clip = speech.ensure(currentBook.book.id, currentBook.sentences[index], currentBook.book.language)
                ensureActiveGeneration(token)
                clip.durationMs
            }
            ensureActiveGeneration(token)
            publishPreparing(currentBook, target.index, target.positionMs)
            openSentence(currentBook, target.index, target.positionMs, token)
            relativeSeeks.complete(request)
        }
    }

    private fun moveRelative(delta: Int) {
        val currentBook = book ?: return
        val target = currentIndex() + delta
        if (target in currentBook.sentences.indices) moveTo(target, 0)
    }

    private fun requestPlayback(play: Boolean) {
        player.playWhenReady = play
        if (!play) { refreshSnapshot(); saveCurrentLater(); return }
        if (book == null && transitionToken == null) {
            PlaybackHub.state.value.bookId?.let { playBook(it, -1, 0) }
            return
        }
        if (transitionToken == null && book != null &&
            (player.playerError != null || player.mediaItemCount == 0 ||
                player.playbackState == Player.STATE_ENDED)) {
            moveTo(PlaybackHub.state.value.sentenceIndex,
                if (player.playbackState == Player.STATE_ENDED) 0 else PlaybackHub.state.value.positionMs)
        } else {
            refreshSnapshot()
        }
    }

    /** Cancels and joins both workers before the next generation may touch TTS or the queue. */
    private fun beginTransition(block: suspend (Long) -> Unit) {
        val oldOperation = operation
        val oldPrefetch = prefetch
        oldOperation?.cancel()
        oldPrefetch?.cancel()
        val token = ++generation
        transitionToken = token
        PlaybackHub.publish(PlaybackHub.state.value.copy(isPreparing = true, isPlaying = false,
            error = null, needsVoiceData = false))
        player.stop()
        player.clearMediaItems()
        try { showPreparingNotification() }
        catch (_: RuntimeException) {
            reportFailure(AudioFailure(getString(R.string.audio_start_failed)))
            return
        }
        operation = serviceScope.launch {
            try {
                oldOperation?.join()
                oldPrefetch?.join()
                ensureActiveGeneration(token)
                speech.protect(emptyList())
                block(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token) reportFailure(failure)
            }
        }
    }

    private suspend fun openSentence(currentBook: SpeechBook, index: Int, position: Long, token: Long) {
        val clip = speech.ensure(currentBook.book.id, currentBook.sentences[index], currentBook.book.language)
        ensureActiveGeneration(token)
        clips.clear()
        clips[index] = clip
        speech.protect(listOf(clip.file))
        player.setMediaItem(mediaItem(currentBook, index, clip), position.coerceIn(0, clip.durationMs))
        transitionToken = null
        player.prepare()
        refreshSnapshot()
        saveCurrentLater()
        startPrefetch()
    }

    private fun startPrefetch() {
        val currentBook = book ?: return
        if (transitionToken != null || prefetch?.isActive == true || player.mediaItemCount == 0) return
        val token = generation
        prefetch = serviceScope.launch {
            try {
                while (true) {
                    val tail = player.getMediaItemAt(player.mediaItemCount - 1).mediaId.toInt()
                    if (tail >= currentBook.sentences.lastIndex ||
                        tail >= currentIndex() + PREFETCH_SENTENCES) break
                    val index = tail + 1
                    val clip = speech.ensure(currentBook.book.id,
                        currentBook.sentences[index], currentBook.book.language)
                    ensureActiveGeneration(token)
                    clips[index] = clip
                    player.addMediaItem(mediaItem(currentBook, index, clip))
                    protectQueue()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Keep existing audio playing. When playback reaches the missing sentence,
                // openSentence retries and exposes any persistent error to the user.
            }
        }
    }

    private fun prunePlayedQueue() {
        val current = player.currentMediaItemIndex
        if (current > 1) player.removeMediaItems(0, current - 1)
        val first = if (player.mediaItemCount > 0) player.getMediaItemAt(0).mediaId.toIntOrNull() else null
        if (first != null) clips.keys.removeAll { it < first }
        protectQueue()
    }

    private fun protectQueue() {
        val files = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).mediaId.toIntOrNull()?.let { index -> clips[index]?.file }
        }
        speech.protect(files)
    }

    private fun mediaItem(currentBook: SpeechBook, index: Int, clip: SynthesizedSentence): MediaItem {
        val sentence = currentBook.sentences[index]
        val metadata = MediaMetadata.Builder().setTitle(currentBook.book.title)
            .setArtist(sentence.chapterTitle).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        currentBook.book.coverPath?.let { metadata.setArtworkUri(Uri.fromFile(File(it))) }
        return MediaItem.Builder().setMediaId(index.toString()).setUri(Uri.fromFile(clip.file))
            .setMediaMetadata(metadata.build()).build()
    }

    private fun currentIndex(): Int = player.currentMediaItem?.mediaId?.toIntOrNull()
        ?: PlaybackHub.state.value.sentenceIndex

    private fun refreshSnapshot() {
        val currentBook = book ?: return
        // A failed transition leaves no media item. Its zero player position is not
        // authoritative; retain the requested cursor for retry and progress persistence.
        if (transitionToken != null || player.mediaItemCount == 0) return
        val index = currentIndex().coerceIn(0, currentBook.sentences.lastIndex)
        val sentence = currentBook.sentences[index]
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = clips[index]?.durationMs ?: 0
        val chapter = chapterProgressIndex?.at(index, position, duration)
        val old = PlaybackHub.state.value
        PlaybackHub.publish(old.copy(bookId = currentBook.book.id, bookTitle = currentBook.book.title,
            sentenceIndex = index, sentenceCount = currentBook.sentences.size,
            chapterTitle = sentence.chapterTitle, text = sentence.text,
            positionMs = position, durationMs = duration,
            chapterProgress = chapter?.fraction ?: 0f,
            chapterSentenceCount = chapter?.sentenceCount ?: 0,
            isPlaying = player.isPlaying, isPreparing = player.playbackState == Player.STATE_BUFFERING,
            speed = player.playbackParameters.speed))
    }

    private fun publishPreparing(currentBook: SpeechBook, index: Int, positionMs: Long) {
        val sentence = currentBook.sentences[index]
        val duration = clips[index]?.durationMs ?: 0
        val chapter = chapterProgressIndex?.at(index, positionMs, duration)
        PlaybackHub.publish(PlaybackHub.state.value.copy(bookId = currentBook.book.id,
            bookTitle = currentBook.book.title, sentenceIndex = index,
            sentenceCount = currentBook.sentences.size, chapterTitle = sentence.chapterTitle,
            text = sentence.text, positionMs = positionMs.coerceAtLeast(0), durationMs = duration,
            chapterProgress = chapter?.fraction ?: 0f,
            chapterSentenceCount = chapter?.sentenceCount ?: 0,
            isPreparing = true, isPlaying = false, error = null, needsVoiceData = false))
    }

    private fun captureProgress(): ReadingProgress? {
        val currentBook = book ?: return null
        val index = currentIndex().coerceIn(0, currentBook.sentences.lastIndex)
        val position = if (transitionToken == null && player.mediaItemCount > 0)
            player.currentPosition.coerceAtLeast(0) else PlaybackHub.state.value.positionMs
        return ReadingProgress(currentBook.book.id, currentBook.sentences[index].locatorJson, index, position)
    }
    private fun saveCurrentLater() {
        val progress = captureProgress() ?: return
        serviceScope.launch { persist(progress) }
    }
    private suspend fun persist(progress: ReadingProgress) {
        val sequence = saveSequence.incrementAndGet()
        latestSave[progress.bookId] = sequence
        try {
            withContext(Dispatchers.IO) {
                persistenceMutex.withLock {
                    if (latestSave[progress.bookId] == sequence) repository.saveProgress(progress)
                }
            }
            if (PlaybackHub.state.value.bookId == progress.bookId &&
                PlaybackHub.state.value.error == getString(R.string.audio_progress_failed)) {
                PlaybackHub.publish(PlaybackHub.state.value.copy(error = null))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (PlaybackHub.state.value.bookId == progress.bookId) withContext(Dispatchers.Main.immediate) {
                PlaybackHub.publish(PlaybackHub.state.value.copy(error = getString(R.string.audio_progress_failed)))
            }
        }
    }

    private fun stopPlayback() {
        relativeSeeks.clear()
        val progress = captureProgress()
        val oldOperation = operation
        val oldPrefetch = prefetch
        oldOperation?.cancel()
        oldPrefetch?.cancel()
        val token = ++generation
        transitionToken = token
        book = null
        PlaybackHub.publish(PlaybackHub.state.value.copy(isPlaying = false, isPreparing = false))
        player.pause()
        player.stop()
        player.clearMediaItems()
        operation = serviceScope.launch {
            oldOperation?.join()
            oldPrefetch?.join()
            if (progress != null) runCatching { persist(progress) }
            if (generation != token) return@launch
            clips.clear()
            chapterProgressIndex = null
            speech.protect(emptyList())
            transitionToken = null
            PlaybackHub.publish(PlaybackSnapshot(speed = player.playbackParameters.speed))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun reportFailure(failure: Exception) {
        relativeSeeks.clear()
        generation += 1
        prefetch?.cancel()
        operation?.cancel()
        speech.resetEngine()
        transitionToken = null
        player.pause()
        val audio = failure as? AudioFailure
        PlaybackHub.publish(PlaybackHub.state.value.copy(isPlaying = false, isPreparing = false,
            error = audio?.userMessage ?: getString(R.string.audio_playback_failed),
            needsVoiceData = audio?.needsVoiceData == true))
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun ensureActiveGeneration(token: Long) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException("Playback generation replaced")
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (transitionToken != null) showPreparingNotification()
        else super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun showPreparingNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.audio_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 10, PlaybackHub.command(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PlaybackHub.state.value.bookTitle.ifBlank {
                getString(R.string.audio_notification_title) })
            .setContentText(getString(R.string.audio_preparing)).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.audio_stop), stop)
            .apply { launchPendingIntent()?.let { setContentIntent(it) } }.build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun launchPendingIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady && transitionToken == null) stopPlayback()
    }

    override fun onDestroy() {
        val progress = captureProgress()
        val store = repository
        val storeMutex = persistenceMutex
        generation += 1
        serviceScope.cancel()
        speech.close()
        session.release()
        player.release()
        PlaybackHub.serviceAlive = false
        PlaybackHub.publish(PlaybackSnapshot(speed = PlaybackHub.state.value.speed))
        // onDestroy cannot suspend. The repository never recreates a removed book directory.
        if (progress != null) CoroutineScope(Dispatchers.IO).launch {
            storeMutex.withLock {
                runCatching {
                    if (store.progress(progress.bookId).updatedAt <= progress.updatedAt) store.saveProgress(progress)
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_PLAY_BOOK = "com.freereading.audio.PLAY_BOOK"
        internal const val ACTION_TOGGLE = "com.freereading.audio.TOGGLE"
        internal const val ACTION_SEEK_BY = "com.freereading.audio.SEEK_BY"
        internal const val ACTION_SPEED = "com.freereading.audio.SPEED"
        internal const val ACTION_NEXT = "com.freereading.audio.NEXT"
        internal const val ACTION_PREVIOUS = "com.freereading.audio.PREVIOUS"
        internal const val ACTION_STOP = "com.freereading.audio.STOP"
        private val PRIVATE_ACTIONS = setOf(ACTION_PLAY_BOOK, ACTION_TOGGLE, ACTION_SEEK_BY,
            ACTION_SPEED, ACTION_NEXT, ACTION_PREVIOUS, ACTION_STOP)
        internal const val EXTRA_BOOK_ID = "book_id"
        internal const val EXTRA_INDEX = "sentence_index"
        internal const val EXTRA_POSITION = "position_ms"
        internal const val EXTRA_DELTA = "delta_ms"
        internal const val EXTRA_SPEED = "speed"
        private const val SEEK_MS = 15_000L
        private const val PREFETCH_SENTENCES = 4
        private const val CHANNEL_ID = "reading_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
