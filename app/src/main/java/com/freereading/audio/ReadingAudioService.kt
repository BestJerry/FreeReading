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

/**
 * 独立于阅读页面的后台听书服务，统一持有播放器、媒体会话、TTS 缓存及进度保存任务。
 * 播放操作在主线程串行协调；每次跳转递增 generation，旧合成／预取完成后不得覆盖新目标。
 */
@UnstableApi
class ReadingAudioService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as ReadingApplication).repository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var speech: OfflineSpeechCache
    private var book: SpeechBook? = null
    private var chapterProgressIndex: ChapterProgressIndex? = null
    // generation 标识当前有效操作；transitionToken 非空时，界面快照保留的是待播放目标。
    private var generation = 0L
    private var transitionToken: Long? = null
    private var operation: Job? = null
    private var prefetch: Job? = null
    private var voiceLookup: Job? = null
    private var voiceLookupRequestId: String? = null
    private var voiceLookupSelecting = false
    private var latestVoiceRequestId: String? = null
    private var switchingVoiceRequestId: String? = null
    private var switchingVoiceCatalog: TtsVoiceCatalog? = null
    private var destroyed = false
    private var latestStartId = 0
    private val clips = mutableMapOf<Int, SynthesizedSentence>()
    private val relativeSeeks = RelativeSeekAccumulator()
    private val persistenceMutex = Mutex()
    private val saveSequence = AtomicLong()
    private val latestSave = ConcurrentHashMap<String, Long>()

    /** 初始化播放器与系统媒体控制，将通知栏／耳机操作接入同一播放策略，并定期发布和保存进度。 */
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

    /** 仅向系统信任的控制器或本应用提供媒体会话。 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.isTrusted || controllerInfo.packageName == packageName) session else null

    /** 验证应用私有命令，再分发起播、跳转和音色请求；系统媒体 Intent 交由 Media3 处理。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent != null) {
            val action = intent.action
            if (action != null && action in PRIVATE_ACTIONS && !PlaybackHub.isAuthorizedCommand(intent)) {
                // 拒绝伪造的应用私有命令，同时保留已经存在的播放会话。
                if (book == null && transitionToken == null && player.mediaItemCount == 0) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_PLAY_BOOK -> {
                val id = intent.getStringExtra(EXTRA_BOOK_ID)
                val requestId = intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID)
                // 前台服务启动时限也包含 EPUB 解析和 TTS 初始化耗时，
                // 必须在启动这两个耗时操作之前先显示前台通知。
                try { showPreparingNotification() }
                catch (_: RuntimeException) {
                    val old = PlaybackHub.state.value
                    val startFailure = if (requestId != null && !id.isNullOrBlank()) {
                        PlaybackStartFailure(requestId, id, intent.getIntExtra(EXTRA_INDEX, -1),
                            intent.getLongExtra(EXTRA_POSITION, 0).coerceAtLeast(0))
                    } else old.startFailure
                    PlaybackHub.publish(old.copy(isPreparing = false, isPlaying = false,
                        error = getString(R.string.audio_start_failed), startFailure = startFailure))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (id.isNullOrBlank()) { stopPlayback(); return START_NOT_STICKY }
                playBook(id, intent.getIntExtra(EXTRA_INDEX, -1),
                    intent.getLongExtra(EXTRA_POSITION, 0),
                    requestId ?: PlaybackHub.newPlaybackSessionId(), PlaybackHub.followRequestId(intent))
            }
            ACTION_TOGGLE -> requestPlayback(if (player.playbackState == Player.STATE_ENDED)
                true else !player.playWhenReady, PlaybackHub.followRequestId(intent))
            ACTION_SEEK_BY -> seekBy(intent.getLongExtra(EXTRA_DELTA, 0))
            ACTION_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1f)
                if (speed.isFinite()) player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
                refreshSnapshot()
            }
            ACTION_NEXT -> moveRelative(1, PlaybackHub.followRequestId(intent))
            ACTION_PREVIOUS -> moveRelative(-1, PlaybackHub.followRequestId(intent))
            ACTION_NEXT_CHAPTER -> moveChapter(intent.getStringExtra(EXTRA_BOOK_ID), 1, PlaybackHub.followRequestId(intent))
            ACTION_PREVIOUS_CHAPTER -> moveChapter(intent.getStringExtra(EXTRA_BOOK_ID), -1, PlaybackHub.followRequestId(intent))
            ACTION_STOP -> stopPlayback()
            ACTION_LOAD_VOICES, ACTION_SELECT_VOICE -> handleVoiceRequest(intent)
            else -> return super.onStartCommand(intent, flags, startId)
        }
        return START_NOT_STICKY
    }

    /**
     * 按最新请求标识处理音色查询或切换，拒绝其他书籍改变当前会话的音色。
     * 为当前书籍切换音色时保留句内比例、播放意图和倍速；仅查询音色目录时不打断首句准备。
     */
    private fun handleVoiceRequest(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        val language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_VOICE_REQUEST_ID) ?: return
        // 迟到的 startService 命令不能替换界面已展示的较新请求。
        if (PlaybackHub.voiceState.value.requestId != requestId) return
        latestVoiceRequestId = requestId
        val selecting = intent.action == ACTION_SELECT_VOICE
        val currentBook = book
        val catalogLanguage = currentBook?.takeIf { it.book.id == id }?.book?.language ?: language
        val owner = currentBook?.book?.id ?: PlaybackHub.state.value.bookId.takeIf {
            transitionToken != null || player.mediaItemCount > 0
        }
        if (selecting && owner != null && owner != id) {
            publishVoiceResult(requestId, error = getString(R.string.audio_voice_other_book))
            return
        }
        if (selecting && owner != null && currentBook == null) {
            publishVoiceResult(requestId, error = getString(R.string.audio_voice_request_interrupted))
            return
        }
        val voiceId = intent.getStringExtra(EXTRA_VOICE_ID)
        if (selecting && currentBook != null) {
            relativeSeeks.clear()
            val index = currentIndex().coerceIn(0, currentBook.sentences.lastIndex)
            val old = PlaybackHub.state.value
            val position = if (transitionToken == null && player.mediaItemCount > 0)
                player.currentPosition.coerceAtLeast(0) else old.positionMs
            val duration = clips[index]?.durationMs ?: old.durationMs
            val fraction = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else null
            // 清空队列会丢失播放器当前项，因此先保存此处的句子游标。
            publishPreparing(currentBook, index, position)
            beginTransition(voiceRequestId = requestId) { token ->
                val catalog = speech.selectVoice(currentBook.book.language, voiceId)
                ensureActiveGeneration(token)
                switchingVoiceCatalog = catalog
                openSentence(currentBook, index, position, token, fraction)
                if (switchingVoiceRequestId == requestId) {
                    switchingVoiceRequestId = null
                    switchingVoiceCatalog = null
                }
                publishVoiceResult(requestId, catalog)
            }
            return
        }

        val oldLookup = voiceLookup
        val oldPrefetch = prefetch
        // 取消旧任务前先替换守卫，避免旧 finally 同步执行时误重启预取。
        voiceLookupRequestId = requestId
        voiceLookupSelecting = selecting
        // 查询音色不等待推测性的后续句合成，也不终止首句音频准备。
        oldLookup?.cancel()
        oldPrefetch?.cancel()
        val pendingOperation = operation.takeIf { transitionToken != null }
        val token = generation
        voiceLookup = serviceScope.launch {
            try {
                oldLookup?.join()
                oldPrefetch?.join()
                pendingOperation?.join()
                val catalog = if (selecting) speech.selectVoice(catalogLanguage, voiceId) else speech.voices(catalogLanguage)
                publishVoiceResult(requestId, catalog)
            } catch (cancelled: CancellationException) {
                // resetEngine() 可只取消初始化等待而不销毁服务；
                // 主线程收到结果前，音色选择也可能已经保存，不能继续展示旧选择已确认。
                publishVoiceResult(requestId, error = getString(R.string.audio_voice_request_interrupted),
                    clearCatalog = selecting)
                throw cancelled
            } catch (failure: Exception) {
                publishVoiceResult(requestId, error = voiceFailureMessage(failure))
            } finally {
                if (voiceLookupRequestId == requestId) {
                    voiceLookupRequestId = null
                    if (!destroyed && generation == token) startPrefetch()
                    if (!destroyed && book == null && transitionToken == null && player.mediaItemCount == 0) {
                        // 后续 STOP 也需要清理；已接受的 PLAY 会持有书籍或切换标记，不会在此被停止。
                        stopSelf(latestStartId)
                    }
                }
            }
        }
    }

    /** 仅向仍在等待该请求的界面发布结果，防止销毁或连续选择后的旧结果回写。 */
    private fun publishVoiceResult(requestId: String, catalog: TtsVoiceCatalog? = null,
                                   error: String? = null, clearCatalog: Boolean = false) {
        if (destroyed || latestVoiceRequestId != requestId || PlaybackHub.voiceState.value.requestId != requestId) return
        val old = PlaybackHub.voiceState.value
        PlaybackHub.publishVoices(old.copy(loading = false, switching = false,
            catalog = if (clearCatalog) null else catalog ?: old.catalog, error = error))
    }

    private fun voiceFailureMessage(failure: Exception): String =
        (failure as? AudioFailure)?.userMessage ?: getString(R.string.audio_voice_selection_failed)

    /** 中断音色切换并结束弹窗等待；若偏好是否已保存尚不明确，则清除旧目录等待重新读取。 */
    private fun interruptVoiceSwitch(error: String = getString(R.string.audio_voice_request_interrupted)) {
        switchingVoiceRequestId?.let { requestId ->
            if (switchingVoiceCatalog == null && latestVoiceRequestId == requestId &&
                PlaybackHub.voiceState.value.requestId == requestId) {
                // 取消可能发生在 IO 已保存偏好、主线程尚未收到结果之间，
                // 不能把原来的单选项继续当作已确认的当前音色。
                PlaybackHub.publishVoices(PlaybackHub.voiceState.value.copy(loading = false,
                    switching = false, catalog = null, error = error))
            } else publishVoiceResult(requestId, switchingVoiceCatalog, error)
        }
        switchingVoiceRequestId = null
        switchingVoiceCatalog = null
    }

    /** 建立新的起播会话，后台读取索引；负索引恢复保存的位置，显式索引则从指定句内位置播放。 */
    private fun playBook(id: String, index: Int, offsetMs: Long,
                         playbackSessionId: String = PlaybackHub.newPlaybackSessionId(),
                         followRequestId: String = PlaybackHub.newFollowRequestId()) {
        relativeSeeks.clear()
        val previousProgress = captureProgress()
        book = null
        PlaybackHub.publish(PlaybackSnapshot(bookId = id,
            bookTitle = repository.books.value.firstOrNull { it.id == id }?.title.orEmpty(),
            sentenceIndex = index.coerceAtLeast(0), speed = player.playbackParameters.speed,
            playbackSessionId = playbackSessionId, followRequestId = followRequestId))
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

    /** 跳到绝对句子位置并替换旧任务；章节按钮可明确要求开播，普通跳转保留当前播放意图。 */
    private fun moveTo(index: Int, positionMs: Long, startPlaying: Boolean = false,
                       restoreFollow: Boolean = false, followRequestId: String? = null) {
        val currentBook = book ?: return
        relativeSeeks.clear()
        val progress = captureProgress()
        val target = index.coerceIn(0, currentBook.sentences.lastIndex)
        // 保存进度或合成音频前先发布目标，让连续点击从待播放句继续计算，
        // 避免旧播放器位置导致每次点击都跳向同一个目标。
        publishPreparing(currentBook, target, positionMs, restoreFollow, followRequestId)
        beginTransition(playWhenReady = if (startPlaying) true else null) { token ->
            progress?.let { persist(it) }
            openSentence(currentBook, target, positionMs, token)
        }
    }

    /** 累加相对快进／快退，并按真实句长跨句定位；缺少的音频按需合成，不使用估算时长。 */
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

    /** 处理系统媒体的上一项／下一项，仍按句子切换并保留播放／暂停状态。 */
    private fun moveRelative(delta: Int, followRequestId: String? = null) {
        val currentBook = book ?: return
        val target = currentIndex() + delta
        if (target in currentBook.sentences.indices) {
            moveTo(target, 0, restoreFollow = player.playWhenReady, followRequestId = followRequestId)
        }
    }

    /** 仅允许当前书籍跳至相邻章首并播放；首末章没有目标时不重启当前音频。 */
    private fun moveChapter(requestedBookId: String?, delta: Int, followRequestId: String? = null) {
        val currentBook = book ?: return
        if (requestedBookId != currentBook.book.id) return
        val chapters = chapterProgressIndex ?: return
        val index = currentIndex().coerceIn(currentBook.sentences.indices)
        val target = if (delta < 0) chapters.previousChapterStart(index) else chapters.nextChapterStart(index)
        if (target != null) moveTo(target, 0, startPlaying = true, restoreFollow = true,
            followRequestId = followRequestId)
    }

    /** 记录最新播放意图；暂停立即生效，恢复时可重建失败、空队列或已结束的当前句。 */
    private fun requestPlayback(play: Boolean, followRequestId: String? = null) {
        val restarting = transitionToken == null && book != null &&
            (player.playerError != null || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED)
        val restoreFollow = play && (!player.playWhenReady || restarting) &&
            (book != null || (transitionToken != null && PlaybackHub.state.value.isPreparing))
        player.playWhenReady = play
        if (!play) { refreshSnapshot(); saveCurrentLater(); return }
        if (book == null && transitionToken == null) {
            PlaybackHub.state.value.bookId?.let {
                playBook(it, -1, 0, followRequestId = followRequestId ?: PlaybackHub.newFollowRequestId())
            }
            return
        }
        if (restoreFollow) {
            PlaybackHub.publish(PlaybackHub.state.value.copy(
                followRequestId = followRequestId ?: PlaybackHub.newFollowRequestId()))
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

    /**
     * 建立新一代播放操作，取消旧操作与预取，等待它们及既有音色查询退出后才允许新任务合成并安装音频。
     * 先发布切换标记并清空旧队列；其后到达的暂停命令优先于本次要求开播的初始意图。
     */
    private fun beginTransition(voiceRequestId: String? = null, playWhenReady: Boolean? = null,
                                block: suspend (Long) -> Unit) {
        val oldOperation = operation
        val oldPrefetch = prefetch
        val pendingVoiceLookup = voiceLookup
        // 先记录切换状态，防止取消导致旧查询的 finally 立即执行并误恢复预取。
        val token = ++generation
        transitionToken = token
        if ((!voiceLookupSelecting || voiceRequestId != null) && pendingVoiceLookup?.isActive == true) {
            voiceLookupRequestId?.let { publishVoiceResult(it,
                error = getString(R.string.audio_voice_request_interrupted)) }
            pendingVoiceLookup.cancel()
        }
        oldOperation?.cancel()
        oldPrefetch?.cancel()
        interruptVoiceSwitch()
        switchingVoiceRequestId = voiceRequestId
        PlaybackHub.publish(PlaybackHub.state.value.copy(isPreparing = true, isPlaying = false,
            error = null, needsVoiceData = false))
        player.stop()
        player.clearMediaItems()
        // 章节按钮在此请求播放，其后到达的暂停命令仍具有最终决定权。
        if (playWhenReady != null) player.playWhenReady = playWhenReady
        try { showPreparingNotification() }
        catch (_: RuntimeException) {
            reportFailure(AudioFailure(getString(R.string.audio_start_failed)))
            return
        }
        operation = serviceScope.launch {
            try {
                oldOperation?.join()
                oldPrefetch?.join()
                pendingVoiceLookup?.join()
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

    /** 合成并安装目标句；检查操作代际后才修改队列，音色切换时按新时长恢复句内比例。 */
    private suspend fun openSentence(currentBook: SpeechBook, index: Int, position: Long, token: Long,
                                     resumeFraction: Double? = null) {
        val clip = speech.ensure(currentBook.book.id, currentBook.sentences[index], currentBook.book.language)
        ensureActiveGeneration(token)
        clips.clear()
        clips[index] = clip
        speech.protect(listOf(clip.file))
        // 不同音色的时长不同，只能近似保留该句的相对播放位置。
        val resumePosition = resumeFraction?.let { (it * clip.durationMs).toLong() } ?: position
        player.setMediaItem(mediaItem(currentBook, index, clip), resumePosition.coerceIn(0, clip.durationMs))
        transitionToken = null
        player.prepare()
        refreshSnapshot()
        saveCurrentLater()
        startPrefetch()
    }

    /** 在当前队列后少量预合成后续句；切换或音色查询期间不竞争 TTS，失败留待实际播放时重试。 */
    private fun startPrefetch() {
        val currentBook = book ?: return
        if (transitionToken != null || voiceLookupRequestId != null ||
            prefetch?.isActive == true || player.mediaItemCount == 0) return
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
                // 预取失败不打断已有音频；真正播放到缺失句时由 openSentence 重试，
                // 持续存在的错误届时才反馈给用户。
            }
        }
    }

    /** 裁掉较早的已播媒体项，只保留当前句附近队列，并同步更新缓存文件保护范围。 */
    private fun prunePlayedQueue() {
        val current = player.currentMediaItemIndex
        if (current > 1) player.removeMediaItems(0, current - 1)
        val first = if (player.mediaItemCount > 0) player.getMediaItemAt(0).mediaId.toIntOrNull() else null
        if (first != null) clips.keys.removeAll { it < first }
        protectQueue()
    }

    /** 播放队列仍引用的文件不能被容量回收删除。 */
    private fun protectQueue() {
        val files = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).mediaId.toIntOrNull()?.let { index -> clips[index]?.file }
        }
        speech.protect(files)
    }

    /** 以全书句子索引作为媒体标识，附带书名、章名及封面供系统媒体控制展示。 */
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

    /** 从有效媒体项刷新界面状态和正文进度；准备或失败造成的空队列不能覆盖待重试游标。 */
    private fun refreshSnapshot() {
        val currentBook = book ?: return
        // 切换失败后可能没有媒体项，此时播放器的零位置不代表实际目标，
        // 应保留请求游标，供重试和进度保存使用。
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
            canGoToPreviousChapter = chapterProgressIndex?.previousChapterStart(index) != null,
            canGoToNextChapter = chapterProgressIndex?.nextChapterStart(index) != null,
            isPlaying = player.isPlaying, isPreparing = player.playbackState == Player.STATE_BUFFERING,
            speed = player.playbackParameters.speed))
    }

    /** 合成前发布目标句、位置及章节边界，让原文跟随与连续章节操作使用同一待播放游标。 */
    private fun publishPreparing(currentBook: SpeechBook, index: Int, positionMs: Long,
                                 restoreFollow: Boolean = false, followRequestId: String? = null) {
        val sentence = currentBook.sentences[index]
        val duration = clips[index]?.durationMs ?: 0
        val chapter = chapterProgressIndex?.at(index, positionMs, duration)
        val old = PlaybackHub.state.value
        PlaybackHub.publish(old.copy(bookId = currentBook.book.id,
            bookTitle = currentBook.book.title, sentenceIndex = index,
            sentenceCount = currentBook.sentences.size, chapterTitle = sentence.chapterTitle,
            text = sentence.text, positionMs = positionMs.coerceAtLeast(0), durationMs = duration,
            chapterProgress = chapter?.fraction ?: 0f,
            chapterSentenceCount = chapter?.sentenceCount ?: 0,
            canGoToPreviousChapter = chapterProgressIndex?.previousChapterStart(index) != null,
            canGoToNextChapter = chapterProgressIndex?.nextChapterStart(index) != null,
            followRequestId = if (restoreFollow) followRequestId ?: PlaybackHub.newFollowRequestId() else old.followRequestId,
            isPreparing = true, isPlaying = false, error = null, needsVoiceData = false))
    }

    /** 捕获可持久化的听书位置；切换期间使用请求位置，正常播放期间读取真实句内媒体时间。 */
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
    /** 按书籍记录最新保存序号，互斥写盘时跳过旧请求，避免异步保存把进度回退。 */
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

    /** 立即停播并禁用章节按钮，等待旧播放／预取任务退出并尝试保存进度后才清空会话、移除前台通知。 */
    private fun stopPlayback() {
        interruptVoiceSwitch()
        relativeSeeks.clear()
        val progress = captureProgress()
        val oldOperation = operation
        val oldPrefetch = prefetch
        oldOperation?.cancel()
        oldPrefetch?.cancel()
        val token = ++generation
        transitionToken = token
        book = null
        PlaybackHub.publish(PlaybackHub.state.value.copy(isPlaying = false, isPreparing = false,
            canGoToPreviousChapter = false, canGoToNextChapter = false))
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
            PlaybackHub.publish(PlaybackSnapshot(speed = player.playbackParameters.speed,
                startFailure = PlaybackHub.state.value.startFailure))
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (voiceLookupRequestId == null) stopSelf()
        }
    }

    /** 使旧任务失效并释放异常引擎，暂停音频但保留当前目标，供用户修复语音或重试。 */
    private fun reportFailure(failure: Exception) {
        interruptVoiceSwitch(voiceFailureMessage(failure))
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

    /** 挂起任务恢复后同时检查协程取消和操作代际，禁止旧结果重新安装音频。 */
    private suspend fun ensureActiveGeneration(token: Long) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException("Playback generation replaced")
    }

    /** 准备期间保持应用自己的前台通知，媒体已就绪时交回 Media3 更新。 */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (transitionToken != null) showPreparingNotification()
        else super.onUpdateNotification(session, startInForegroundRequired)
    }

    /** 在解析或合成前满足媒体前台服务要求，并提供带授权令牌的停止按钮。 */
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

    /** 划走任务时保留有播放意图或仍在切换的会话，仅在 playWhenReady 为 false 且无切换任务时停止。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady && transitionToken == null) stopPlayback()
    }

    /** 取消异步操作并释放 TTS、媒体会话和播放器；销毁前捕获的进度在后台尽力保存。 */
    override fun onDestroy() {
        destroyed = true
        val voiceState = PlaybackHub.voiceState.value
        if (voiceState.loading || voiceState.switching) PlaybackHub.publishVoices(voiceState.copy(
            loading = false, switching = false,
            catalog = if (voiceState.switching || switchingVoiceRequestId != null)
                switchingVoiceCatalog else voiceState.catalog,
            error = getString(R.string.audio_voice_request_interrupted)))
        val progress = captureProgress()
        val store = repository
        val storeMutex = persistenceMutex
        generation += 1
        serviceScope.cancel()
        speech.close()
        session.release()
        player.release()
        PlaybackHub.serviceAlive = false
        PlaybackHub.publish(PlaybackSnapshot(speed = PlaybackHub.state.value.speed,
            startFailure = PlaybackHub.state.value.startFailure))
        // onDestroy 不能挂起；后台保存仍由仓库保证不会重新创建已移除书籍的目录。
        if (progress != null) CoroutineScope(Dispatchers.IO).launch {
            storeMutex.withLock {
                runCatching {
                    if (store.progress(progress.bookId).updatedAt <= progress.updatedAt) store.saveProgress(progress)
                }
            }
        }
        super.onDestroy()
    }

    /** 应用私有命令协议、媒体通知标识与预取上限。 */
    companion object {
        internal const val ACTION_PLAY_BOOK = "com.freereading.audio.PLAY_BOOK"
        internal const val ACTION_TOGGLE = "com.freereading.audio.TOGGLE"
        internal const val ACTION_SEEK_BY = "com.freereading.audio.SEEK_BY"
        internal const val ACTION_SPEED = "com.freereading.audio.SPEED"
        internal const val ACTION_NEXT = "com.freereading.audio.NEXT"
        internal const val ACTION_PREVIOUS = "com.freereading.audio.PREVIOUS"
        internal const val ACTION_NEXT_CHAPTER = "com.freereading.audio.NEXT_CHAPTER"
        internal const val ACTION_PREVIOUS_CHAPTER = "com.freereading.audio.PREVIOUS_CHAPTER"
        internal const val ACTION_STOP = "com.freereading.audio.STOP"
        internal const val ACTION_LOAD_VOICES = "com.freereading.audio.LOAD_VOICES"
        internal const val ACTION_SELECT_VOICE = "com.freereading.audio.SELECT_VOICE"
        private val PRIVATE_ACTIONS = setOf(ACTION_PLAY_BOOK, ACTION_TOGGLE, ACTION_SEEK_BY,
            ACTION_SPEED, ACTION_NEXT, ACTION_PREVIOUS, ACTION_NEXT_CHAPTER, ACTION_PREVIOUS_CHAPTER,
            ACTION_STOP, ACTION_LOAD_VOICES, ACTION_SELECT_VOICE)
        internal const val EXTRA_BOOK_ID = "book_id"
        internal const val EXTRA_INDEX = "sentence_index"
        internal const val EXTRA_POSITION = "position_ms"
        internal const val EXTRA_PLAYBACK_SESSION_ID = "playback_session_id"
        internal const val EXTRA_FOLLOW_REQUEST_ID = "follow_request_id"
        internal const val EXTRA_DELTA = "delta_ms"
        internal const val EXTRA_SPEED = "speed"
        internal const val EXTRA_LANGUAGE = "language"
        internal const val EXTRA_VOICE_REQUEST_ID = "voice_request_id"
        internal const val EXTRA_VOICE_ID = "voice_id"
        private const val SEEK_MS = 15_000L
        private const val PREFETCH_SENTENCES = 4
        private const val CHANNEL_ID = "reading_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
