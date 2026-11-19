@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStateAtLeast
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.TtsVoiceUiState
import com.freereading.data.BookRecord
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import com.freereading.ui.FreeReadingTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.use
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily

/**
 * 承载 EPUB 阅读、目录和独立听书界面，协调原文定位、句子高亮与阅读进度。
 * 只通过 PlaybackHub 观察和控制音频，TTS 与播放器的生命周期由后台服务管理。
 * 常规打开优先显示正文，未缓存的听书索引按需加载；异步定位以代次和书籍版本校验，避免旧任务覆盖新操作。
 */
class ReaderActivity : FragmentActivity() {
    private val repository get() = (application as ReadingApplication).repository
    private val bookId get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
    private val preferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var speechBook by mutableStateOf<SpeechBook?>(null)
    private var readingRecord by mutableStateOf<BookRecord?>(null)
    private var speechLoad: Deferred<SpeechBook>? = null
    private var speechGeneration = 0L
    private var lateInitialStopRevision: Long? = null
    private var visibleLocator by mutableStateOf<Locator?>(null)
    private var loading by mutableStateOf(true)
    private var openingError by mutableStateOf<String?>(null)
    private var showContents by mutableStateOf(false)
    private var contentsEntries by mutableStateOf<List<ContentsEntry>>(emptyList())
    private var selectedContentsIndex by mutableStateOf<Int?>(null)
    private var contentsJob: Job? = null
    private var contentsGeneration = 0L
    private var showAppearance by mutableStateOf(false)
    private var showReadingControls by mutableStateOf(false)
    private var showListening by mutableStateOf(false)
    // 每次明确起播／恢复时开启；本次播放中仍允许关闭或手动浏览暂时脱离。
    private var followAudio by mutableStateOf(true)
    private var handledFollowRequestId: String? = null
    private var fontScale by mutableStateOf(1.15f)
    private var pageStyle by mutableStateOf("paper")
    private var scroll by mutableStateOf(false)
    private var loadJob: Job? = null
    private lateinit var root: ReaderSurfaceLayout
    private lateinit var documentFrame: FrameLayout
    private lateinit var openingOverlay: ComposeView
    private val fragmentContainerId = View.generateViewId()
    private var restoredLocator: String? = null
    private var resumableProgress: ReadingProgress? = null
    private var pendingStoppedProgress: ReadingProgress? = null
    private var lastAnchorChapter: Int? = null
    private var chapterAnchors: List<SentenceLocatorMatcher.Anchor> = emptyList()
    private var readingOrderHrefs: List<String> = emptyList()
    private var readingPlaybackIntent by mutableStateOf(ReadingPlaybackIntent.CONTINUE_AUDIO)
    private val followingAudio: Boolean
        get() = followAudio && readingPlaybackIntent == ReadingPlaybackIntent.CONTINUE_AUDIO
    private val dragNavigation by lazy {
        ReaderDragNavigation(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
    }
    private var requestedReadingStart: Locator? = null
    private var readingNavigationRevision = 0L
    private var readingStartJob: Job? = null
    private var pendingAudioSentence: Int? = null
    private var pendingAudioRequestId: String? = null
    private var audioBinding: Job? = null
    private var audioHighlighter: AudioHighlightSynchronizer? = null
    private var audioPageReady = false
    private val sourceResolverMutex = Mutex()
    private var sourceResolver: Pair<String, SourceSentenceResolver>? = null
    /** 绑定视觉页与导航代次的可见文本快照，供 WebView 销毁前后的进度保存复用。 */
    private data class VisibleTextSnapshot(val page: Locator, val textAnchor: Locator, val revision: Long)
    /** 仅保留影响原文同步的播放状态，过滤仅句内音频时间变化的重复同步。 */
    private data class AudioCursor(val bookId: String?, val sentenceIndex: Int, val sentenceCount: Int,
                                   val playing: Boolean, val preparing: Boolean, val sessionId: String?,
                                   val failedStartId: String?, val followRequestId: String?)
    private var visibleTextSnapshot: VisibleTextSnapshot? = null

    private val paper: Color get() = when (pageStyle) {
        "dark" -> Color(0xFF1E2422)
        "white" -> Color(0xFFFCFCF9)
        else -> Color(0xFFF7F2E8)
    }
    private val ink: Color get() = if (pageStyle == "dark") Color(0xFFE4E9E3) else Color(0xFF293C35)
    private val muted: Color get() = ink.copy(alpha = 0.62f)
    private val accent: Color get() = if (pageStyle == "dark") Color(0xFFB5CDB5) else Color(0xFF49674F)

    /** 恢复阅读意图与未确认的起播请求，再重新打开本地书籍并创建导航视图。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        restoredLocator = savedInstanceState?.getString(STATE_LOCATOR)
        followAudio = savedInstanceState?.getBoolean(STATE_FOLLOW, true) ?: true
        handledFollowRequestId = savedInstanceState?.getString(STATE_FOLLOW_REQUEST)
        readingPlaybackIntent = savedInstanceState?.getString(STATE_PLAYBACK_INTENT)?.let {
            runCatching { ReadingPlaybackIntent.valueOf(it) }.getOrNull()
        } ?: ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = savedInstanceState?.getString(STATE_READING_START)?.let(::parseLocator)
        pendingAudioRequestId = savedInstanceState?.getString(STATE_PENDING_AUDIO_REQUEST)
            ?.takeIf(PlaybackHub::isCurrentPlaybackSessionId)
        pendingAudioSentence = savedInstanceState?.getInt(STATE_PENDING_AUDIO_SENTENCE, -1)
            ?.takeIf { it >= 0 && pendingAudioRequestId != null }
        showReadingControls = savedInstanceState?.getBoolean(STATE_READING_CONTROLS, false) ?: false
        showListening = savedInstanceState?.getBoolean(STATE_LISTENING)
            ?: intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        pendingStoppedProgress = savedInstanceState?.getBundle(STATE_STOPPED_PROGRESS)
            ?.takeIf { it.getString("book_id") == bookId }
            ?.let {
                ReadingProgress(bookId, it.getString("locator"), it.getInt("sentence"),
                    it.getLong("offset").coerceAtLeast(0), it.getLong("updated"))
            }
        resumableProgress = pendingStoppedProgress
        // Readium Fragment 持有工厂传入的 Publication；必须先重开文件再重建，
        // 避免系统恢复的 Fragment 引用已经关闭或缺失的书籍。
        super.onCreate(null)
        fontScale = preferences.getFloat("font_scale", 1.15f).coerceIn(0.8f, 2f)
        pageStyle = preferences.getString("page_style", "paper").orEmpty()
        scroll = preferences.getBoolean("scroll", false)
        buildViews()
        openBook()
    }

    /** 固定正文视口尺寸，加载遮罩和菜单叠加其上，防止菜单显隐引起重新分页。 */
    private fun buildViews() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        documentFrame = FrameLayout(this).apply {
            id = fragmentContainerId
            // 阅读状态和菜单状态都只为薄页脚预留相同高度。
            setPadding(0, 0, 0, (28 * resources.displayMetrics.density).toInt())
        }
        openingOverlay = composeView { OpeningState() }
        val chrome = composeView { ReaderSurfaces() }
        root = ReaderSurfaceLayout(this, documentFrame, openingOverlay, chrome).apply {
            setBackgroundColor(paper.toArgb())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        updateSystemBars()
    }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { FreeReadingTheme { content() } }
    }

    /** 取消上次打开和索引任务，在后台准备正文；安装失败或取消时释放尚未接管的书籍。 */
    private fun openBook() {
        loadJob?.cancel()
        speechGeneration += 1
        speechLoad?.cancel()
        speechLoad = null
        speechBook = null
        readingRecord = null
        loading = true
        openingError = null
        openingOverlay.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            var opened: Publication? = null
            var installed = false
            try {
                require(bookId.isNotEmpty()) { "未找到书籍，请返回书架重新打开。" }
                val prepared = repository.prepareReadingBook(bookId)
                val readyPublication = withContext(Dispatchers.IO) {
                    repository.readium.openForReading(repository.bookFile(bookId)).also { opened = it }
                }
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                    installReadingNavigator(readyPublication, prepared.record, prepared.progress,
                        prepared.cachedSpeechBook)
                    installed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loading = false
                openingError = error.message ?: "书籍暂时无法读取，请重新导入 EPUB 文件。"
            } finally {
                if (!installed) opened?.close()
            }
        }
    }

    private fun installNavigator(opened: Publication, book: SpeechBook, saved: ReadingProgress) {
        installReadingNavigator(opened, book.book, saved, book)
    }

    /** 综合重建位置、待确认起播和停止游标决定首屏，连接导航回调但不等待完整听书索引。 */
    private fun installReadingNavigator(opened: Publication, record: BookRecord, saved: ReadingProgress,
                                        cachedBook: SpeechBook?) {
        audioBinding?.cancel()
        audioHighlighter = null
        audioPageReady = false
        speechGeneration += 1
        speechLoad?.cancel()
        speechLoad = null
        speechBook = cachedBook
        readingRecord = record
        lastAnchorChapter = null
        chapterAnchors = emptyList()
        pendingAudioSentence = pendingAudioSentence?.takeIf { it in 0 until record.sentenceCount }
        if (pendingAudioSentence == null) pendingAudioRequestId = null
        publication = opened
        readingOrderHrefs = opened.readingOrder.map { it.url().removeFragment().toString() }
        val audio = PlaybackHub.state.value
        restoreFollowForPlaybackStart(audio)
        acknowledgeAudioStart(audio)
        resumableProgress = resolveReadingResumeProgress(record, saved, pendingStoppedProgress,
            audio.bookId, readingPlaybackIntent, cachedBook)
        val stoppedLocator = resumableProgress?.takeIf { pendingStoppedProgress != null }?.locatorJson
        val pendingAudioLocator = pendingAudioSentence?.let { cachedBook?.sentences?.getOrNull(it)?.locatorJson }
        val initial = when {
            readingPlaybackIntent == ReadingPlaybackIntent.RETRY_AUDIO_START && stoppedLocator != null ->
                restoredLocator ?: stoppedLocator
            followingAudio && pendingAudioLocator != null -> pendingAudioLocator
            followingAudio && stoppedLocator != null -> stoppedLocator
            followingAudio && audio.bookId == bookId && audio.sentenceCount > 0 ->
                cachedBook?.sentences?.getOrNull(audio.sentenceIndex)?.locatorJson ?: restoredLocator ?: saved.locatorJson
            restoredLocator != null -> restoredLocator
            else -> saved.locatorJson
        }?.let(::parseLocator)
        lateInitialStopRevision = readingNavigationRevision.takeIf {
            cachedBook == null && pendingStoppedProgress?.bookId == bookId && stoppedLocator == null &&
                followingAudio && (readingPlaybackIntent != ReadingPlaybackIntent.RETRY_AUDIO_START || restoredLocator == null)
        }
        visibleLocator = initial
        if (cachedBook == null) speechLoader()
        var installedFragment: EpubNavigatorFragment? = null
        val factory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = initial,
            initialPreferences = epubPreferences(),
            listener = object : EpubNavigatorFragment.Listener {
                override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
                    markManualNavigation()
                    requestedReadingStart = publication?.locatorFromLink(link)
                    return true
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    val uri = Uri.parse(url.toString())
                    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                        toast("暂不支持打开这类外部链接。")
                        return
                    }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        toast("未找到可以打开网页的浏览器。")
                    } catch (_: SecurityException) {
                        toast("系统暂时无法打开此链接。")
                    }
                }

                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    toast("这一章节暂时无法显示，可尝试从目录跳转到其他章节。")
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageLoaded() {
                    val fragment = installedFragment ?: return
                    if (navigator !== fragment || fragment.view == null) return
                    audioPageReady = true
                    // 页面就绪前 Readium 可能已缓存高亮却丢弃对应脚本；
                    // 推进渲染代次，确保就绪后真正更新 DOM。
                    audioHighlighter?.onPageLoaded()
                    // 常规阅读在首个可读书页显示后启动索引解码；显式听书可提前启动同一任务。
                    if (speechBook == null && speechLoad?.isCancelled != true) speechLoader().start()
                }
            },
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionMenu,
                shouldApplyInsetsPadding = false,
            ),
        )
        supportFragmentManager.fragmentFactory = factory
        val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        installedFragment = fragment
        navigator = fragment
        observeAudio(fragment)
        fragment.addInputListener(object : InputListener {
            // 消费正文任意区域的普通轻触以统一切换菜单，避免触发 Readium 默认边缘翻页。
            override fun onTap(event: TapEvent): Boolean {
                showReadingControls = !showReadingControls
                return true
            }

            // 这里只记录主动浏览，实际滑动翻页仍交给 Readium 处理。
            override fun onDrag(event: DragEvent): Boolean {
                if (dragNavigation.shouldDetach(event, scroll)) markManualNavigation()
                return false
            }
        })
        supportFragmentManager.beginTransaction()
            .replace(fragmentContainerId, fragment, "epub")
            .commitNow()
        loading = false
        openingOverlay.visibility = View.GONE
        observeLocator(fragment)
    }

    /** 复用同一次延迟索引加载；首屏就绪或显式听书时执行，并校验重新导入与过期代次。 */
    private fun speechLoader(): Deferred<SpeechBook> {
        speechLoad?.takeUnless { it.isCancelled }?.let { return it }
        val token = speechGeneration
        val expectedAddedAt = readingRecord?.addedAt
        return lifecycleScope.async(start = CoroutineStart.LAZY) {
            repository.loadSpeechBook(bookId).also { loaded ->
                check(expectedAddedAt == null || loaded.book.addedAt == expectedAddedAt) {
                    "书籍已重新导入，请返回书架重新打开。"
                }
                if (speechGeneration == token) attachSpeechBook(loaded)
            }
        }.also { speechLoad = it }
    }

    private suspend fun awaitSpeechBook(): SpeechBook = speechBook ?: speechLoader().await()

    /** 补齐听书索引与恢复游标，不替换已显示的阅读视图；只有用户尚未导航时才补跳停止位置。 */
    private fun attachSpeechBook(book: SpeechBook) {
        if (book.book.id != bookId || readingRecord?.let { it.addedAt != book.book.addedAt } == true) return
        speechBook = book
        readingRecord = book.book
        lastAnchorChapter = null
        chapterAnchors = emptyList()
        pendingAudioSentence = pendingAudioSentence?.takeIf { it in book.sentences.indices }
        if (pendingAudioSentence == null) pendingAudioRequestId = null
        val stopped = pendingStoppedProgress
        if (stopped?.bookId == bookId && readingPlaybackIntent != ReadingPlaybackIntent.FROM_READING_LOCATION) {
            val normalized = resolveResumeProgress(book, ReadingProgress(bookId), stopped,
                PlaybackHub.state.value.bookId, readingPlaybackIntent)
            pendingStoppedProgress = normalized
            if (resumableProgress != null) resumableProgress = normalized
            if (lateInitialStopRevision == readingNavigationRevision && followingAudio) {
                normalized?.locatorJson?.let(::parseLocator)?.let { navigator?.go(it, animated = false) }
            }
        }
        lateInitialStopRevision = null
        synchronizeAudio()
        val snapshot = visibleTextSnapshot
        val fragment = navigator
        if (snapshot != null && fragment != null && snapshot.revision == readingNavigationRevision &&
            snapshot.page == fragment.currentLocator.value && canSaveReadingLocation()) {
            lifecycleScope.launch {
                try {
                    // 加载期间已捕获 DOM 锚点，索引就绪后即可补充保存，
                    // 无需等待用户再次翻页触发位置变化。
                    saveReadingLocation(fragment, snapshot.page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("FreeReadingReader", "Could not save the reading position", failure)
                }
            }
        }
    }

    /** 在 Activity 处于 STARTED 及以上状态时观察阅读位置，合并短时间变化以减少重复保存。 */
    @OptIn(FlowPreview::class)
    private fun observeLocator(fragment: EpubNavigatorFragment) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fragment.currentLocator.debounce(250).collect { locator ->
                    visibleLocator = locator
                    saveReadingLocation(fragment, locator)
                }
            }
        }
    }

    /** 跟随 Fragment View 的生命周期重建高亮器，防止重建后的旧视图继续接收音频导航。 */
    private fun observeAudio(fragment: EpubNavigatorFragment) {
        fragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
            if (navigator !== fragment) return@observe
            audioBinding?.cancel()
            audioHighlighter = null
            if (owner == null) {
                audioPageReady = false
                return@observe
            }
            audioBinding = owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val synchronizer = AudioHighlightSynchronizer(
                        scope = this,
                        apply = { fragment.applyDecorations(it, group = "speech") },
                        navigate = { fragment.go(it, animated = false) },
                        canNavigate = { target ->
                            val audio = PlaybackHub.state.value
                            navigator === fragment && fragment.view != null && followingAudio &&
                                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                pendingAudioSentence == null &&
                                audio.bookId == target.bookId && audio.sentenceIndex == target.sentenceIndex
                        },
                    )
                    audioHighlighter = synchronizer
                    if (audioPageReady) synchronizer.onPageLoaded()
                    try {
                        PlaybackHub.state
                            // 同一句的重新起播或恢复也要追赶原文页，不能只比较句子编号。
                            .map { AudioCursor(it.bookId, it.sentenceIndex, it.sentenceCount,
                                it.isPlaying, it.isPreparing, it.playbackSessionId,
                                it.startFailure?.requestId, it.followRequestId) }
                            .distinctUntilChanged()
                            .collect { synchronizeAudio() }
                    } finally {
                        if (audioHighlighter === synchronizer) audioHighlighter = null
                    }
                }
            }
        }
    }

    /** 按当前朗读句请求更新或清除高亮；开启跟随时请求跳页，执行前仍须通过会话与页面校验。 */
    private fun synchronizeAudio() {
        val audio = PlaybackHub.state.value
        restoreFollowForPlaybackStart(audio)
        acknowledgeAudioStart(audio)
        val synchronizer = audioHighlighter ?: return
        val sentence = speechBook?.sentences?.getOrNull(audio.sentenceIndex)
            ?.takeIf { audio.bookId == bookId && audio.sentenceCount > 0 }
        val locator = sentence?.locatorJson?.let(::parseLocator)
        val target = locator?.let { AudioHighlightSynchronizer.Target(bookId, audio.sentenceIndex, it) }
        synchronizer.request(target, navigate = followingAudio)
    }

    /** 消费显式起播事件；不取消正文起播任务，也不从自然换句的播放状态推断恢复跟随。 */
    private fun restoreFollowForPlaybackStart(audio: PlaybackSnapshot) {
        val request = audio.followRequestId ?: return
        if (audio.bookId != bookId || !PlaybackHub.isNewerFollowRequest(request, handledFollowRequestId)) return
        handledFollowRequestId = request
        followAudio = true
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = null
        resumableProgress = null
        pendingStoppedProgress = null
    }

    /** 手动操作记录提交顺序水位，已提交但尚未被服务接收的旧命令也不能再覆盖这次操作。 */
    private fun acknowledgeCurrentFollowRequest() {
        handledFollowRequestId = PlaybackHub.newFollowRequestId()
    }

    /** 按请求 ID 确认起播或记录失败；确认之前不让旧播放状态抢回用户选择的页面。 */
    private fun acknowledgeAudioStart(audio: PlaybackSnapshot) {
        val pending = pendingAudioSentence ?: return
        val failure = audio.startFailure
        if (failure != null && failure.requestId == pendingAudioRequestId && failure.bookId == bookId) {
            audioStartFailed(ReadingProgress(bookId, speechBook?.sentences?.getOrNull(pending)?.locatorJson,
                pending, failure.positionMs.coerceAtLeast(0)))
            return
        }
        if (audio.bookId != bookId) return
        if (PlaybackHub.isPlaybackStartConfirmed(pendingAudioRequestId, audio.playbackSessionId)) {
            // 页面隐藏或重建时可能错过起播句，服务确认该请求或更新的请求后即可解除导航等待。
            clearPendingAudioStart()
        }
    }

    /** 保存视觉页及首个可见段落对应的句子；听书会话持有游标时由服务独占保存。 */
    private suspend fun saveReadingLocation(fragment: EpubNavigatorFragment, page: Locator,
                                           finalSave: Boolean = false) {
        // 本书占有音频会话时，由服务独占保存标准定位和句内偏移，
        // 手动浏览原文不能覆盖听书游标。
        if (!canSaveReadingLocation()) return
        val expectedAddedAt = readingRecord?.addedAt ?: return
        if (speechBook == null) {
            // 趁 WebView 仍存活先捕获 DOM 定位证据，之后再等待索引解码；
            // 即使 Activity 自己的延迟加载已取消，最终保存仍可完成。
            val revision = readingNavigationRevision
            val capturedAt = System.currentTimeMillis()
            val raw = freshVisibleRawAnchor(fragment, page) ?: visibleTextSnapshot?.takeIf {
                it.page == page && it.revision == revision
            }?.textAnchor ?: return
            if (!finalSave || fragment.currentLocator.value != page) return
            val chapter = readingOrderHrefs.indexOf(raw.href.removeFragment().toString())
            if (chapter < 0) return
            saveDeferredReadingLocation(page, raw, chapter, revision, capturedAt, expectedAddedAt)
            return
        }
        val textAnchor = visibleTextAnchor(fragment, page) ?: return
        if (fragment.currentLocator.value != page) return
        val progress = readingProgressAt(page, textAnchor) ?: return
        withContext(Dispatchers.IO) {
            repository.saveProgress(progress, expectedAddedAt = expectedAddedAt)
        }
    }

    /** 最终离页保存可在索引就绪后映射句子；提交前重验导航代次和书籍版本，避免迟到覆盖。 */
    private suspend fun saveDeferredReadingLocation(page: Locator, raw: Locator, chapter: Int,
                                                    revision: Long, capturedAt: Long, expectedAddedAt: Long) {
        val saved = withContext(Dispatchers.IO) {
            val book = repository.loadSpeechBook(bookId)
            val candidates = SpeechSentenceAnchors.build(book.sentences.asSequence().filter { it.chapterIndex == chapter })
            val direct = SentenceLocatorMatcher.find(candidates, raw.toAnchor(-1))
            val index = direct ?: deferredSourceSentenceIndex(raw, candidates)
            index?.let { sentenceIndex ->
                ReadingProgress(bookId, page.toJSON().toString(), sentenceIndex, updatedAt = capturedAt)
            }
        } ?: return
        if (readingNavigationRevision != revision || !canSaveReadingLocation()) return
        withContext(Dispatchers.IO) { repository.saveProgress(saved, expectedAddedAt = expectedAddedAt) }
    }

    /** 最终保存可能晚于 Activity 的 Publication 释放，仅在 DOM 锚点仍未解析时临时重开原文。 */
    private suspend fun deferredSourceSentenceIndex(raw: Locator,
                                                    candidates: List<SentenceLocatorMatcher.Anchor>): Int? {
        val anchor = (raw.locations["cssSelector"] as? String)?.takeIf(String::isNotBlank)
            ?.let { SourceSpeechAnchor.Element(it) }
            ?: raw.locations.fragments.firstOrNull()?.let { SourceSpeechAnchor.Fragment(it) }
            ?: return null
        val href = raw.href.removeFragment()
        return sourceResolverMutex.withLock {
            val cached = sourceResolver?.takeIf { it.first == href.toString() }?.second
            if (cached != null) return@withLock cached.resolve(candidates, anchor)
            val opened = repository.readium.openForReading(repository.bookFile(bookId))
            try {
                val resource = opened.get(href) ?: return@withLock null
                val html = resource.use { it.read().getOrNull()?.decodeString()?.getOrNull() }
                    ?: return@withLock null
                SourceSentenceResolver(html).resolve(candidates, anchor)
            } finally {
                opened.close()
            }
        }
    }

    private fun canSaveReadingLocation(): Boolean =
        PlaybackHub.state.value.bookId != bookId && resumableProgress == null

    /** 视觉定位保留精确页，文本锚点独立提供句子编号，不以页面百分比猜测听书位置。 */
    private fun readingProgressAt(page: Locator, textAnchor: Locator): ReadingProgress? {
        if (!canSaveReadingLocation()) return null
        val sentence = sentenceAt(textAnchor) ?: return null
        // 视觉页与首个可见段落的文本定位各司其职，保留两者而不转换百分比。
        return ReadingProgress(bookId, page.toJSON().toString(), sentence.index)
    }

    /** 从当前 WebView 读取首个可见元素，查询期间翻页或导航意图变化则丢弃结果。 */
    private suspend fun freshVisibleRawAnchor(fragment: EpubNavigatorFragment, page: Locator): Locator? {
        val revision = readingNavigationRevision
        val fresh = queryNavigatorLocator { fragment.firstVisibleElementLocator() }
        if (readingNavigationRevision != revision || fragment.currentLocator.value != page) return null
        if (fresh != null && fresh.href.removeFragment() == page.href.removeFragment()) {
            visibleTextSnapshot = VisibleTextSnapshot(page, fresh, revision)
            return fresh
        }
        return null
    }

    /** 将可见 DOM 元素解析为真实句子定位；文本直接匹配不足时用原始 XHTML 补充判断。 */
    private suspend fun freshVisibleTextAnchor(fragment: EpubNavigatorFragment, page: Locator): Locator? {
        val revision = readingNavigationRevision
        val fresh = freshVisibleRawAnchor(fragment, page)
        if (fresh != null) {
            val sentence = sentenceAt(fresh) ?: fresh.locations["cssSelector"]?.let { selector ->
                (selector as? String)?.takeIf(String::isNotBlank)?.let {
                    resolveSourceSentence(fresh, SourceSpeechAnchor.Element(it))
                }
            }
            if (readingNavigationRevision != revision || fragment.currentLocator.value != page) return null
            val anchor = sentence?.locatorJson?.let(::parseLocator) ?: return null
            visibleTextSnapshot = VisibleTextSnapshot(page, anchor, revision)
            return anchor
        }
        return null
    }

    private suspend fun visibleTextAnchor(fragment: EpubNavigatorFragment, page: Locator): Locator? {
        freshVisibleTextAnchor(fragment, page)?.let { return it }
        // 销毁阶段 WebView 可能已不可用，只复用同一视觉页和导航代次的锚点，
        // 绝不将页面进度换算成正文进度。
        return visibleTextSnapshot?.takeIf {
            it.page == page && it.revision == readingNavigationRevision
        }?.textAnchor
    }

    /** 为 Readium 的 JavaScript 定位请求补充可取消等待与超时，防止最终保存任务被悬挂。 */
    private suspend fun queryNavigatorLocator(query: suspend () -> Locator?): Locator? =
        withTimeoutOrNull(800) {
            // Readium 3.1.2 用不可取消的 suspendCoroutine 包装 evaluateJavascript，
            // 外层使用可取消 continuation，确保视图销毁不会一直拖住最终保存。
            suspendCancellableCoroutine<Locator?> { continuation ->
                val request = lifecycleScope.launch {
                    val result = try {
                        query()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                request.invokeOnCompletion {
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation { request.cancel() }
            }
        }

    /** 主动翻页、目录跳转或选文后临时脱离跟随，并将下一次起播改为新阅读位置。 */
    private fun markManualNavigation() {
        acknowledgeCurrentFollowRequest()
        showReadingControls = false
        cancelReadingStart()
        clearPendingAudioStart()
        readingPlaybackIntent = ReadingPlaybackIntent.FROM_READING_LOCATION
        requestedReadingStart = null
        resumableProgress = null
        pendingStoppedProgress = null
    }

    /** 放弃旧阅读定位与失败重试游标，重新跟随当前音频并恢复自动翻页。 */
    private fun followCurrentAudio() {
        acknowledgeCurrentFollowRequest()
        cancelReadingStart()
        clearPendingAudioStart()
        if (readingPlaybackIntent == ReadingPlaybackIntent.RETRY_AUDIO_START) {
            resumableProgress = null
            pendingStoppedProgress = null
        }
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = null
        followAudio = true
        synchronizeAudio()
    }

    /** 关闭跟随只改变偏好，不改变播放起点；重新开启时请求定位到当前朗读句。 */
    private fun toggleAudioFollow() {
        if (followingAudio) {
            acknowledgeCurrentFollowRequest()
            followAudio = false
            synchronizeAudio()
        } else {
            followCurrentAudio()
        }
    }

    /** 取消未完成的正文起播查询并递增导航代次，让所有迟到定位结果失效。 */
    private fun cancelReadingStart() {
        readingStartJob?.cancel()
        readingStartJob = null
        readingNavigationRevision += 1
        visibleTextSnapshot = null
    }

    private fun clearPendingAudioStart() {
        pendingAudioSentence = null
        pendingAudioRequestId = null
    }

    /** 按正文资源缓存匹配证据，避免每次定位扫描全书或长期保留所有章节的上下文。 */
    private fun anchorsFor(locator: Locator): List<SentenceLocatorMatcher.Anchor> {
        val book = speechBook ?: return emptyList()
        val href = locator.href.removeFragment().toString()
        val chapter = readingOrderHrefs.indexOf(href)
        if (chapter < 0) return emptyList()
        if (lastAnchorChapter != chapter) {
            chapterAnchors = SpeechSentenceAnchors.build(book.sentences.asSequence().filter { it.chapterIndex == chapter })
            lastAnchorChapter = chapter
        }
        return chapterAnchors
    }

    private fun sentenceAt(locator: Locator): SpeechSentence? {
        val book = speechBook ?: return null
        val index = SentenceLocatorMatcher.find(anchorsFor(locator), locator.toAnchor(-1)) ?: return null
        return book.sentences.getOrNull(index)
    }

    /** 在当前资源的原始 DOM 中解析显式锚点，互斥复用解析器；失效锚点不回退到其他章节。 */
    private suspend fun resolveSourceSentence(locator: Locator, anchor: SourceSpeechAnchor): SpeechSentence? {
        val opened = publication ?: return null
        val book = speechBook ?: return null
        val candidates = anchorsFor(locator)
        if (candidates.isEmpty()) return null
        // 资源起点只来自明确的无锚点目录选择，不能用于掩盖解析失败的锚点。
        if (anchor == SourceSpeechAnchor.ResourceStart) return book.sentences.getOrNull(candidates.first().index)
        val href = locator.href.removeFragment()
        val index = withContext(Dispatchers.IO) {
            sourceResolverMutex.withLock {
                val resolver = sourceResolver?.takeIf { it.first == href.toString() }?.second ?: run {
                    val resource = opened.get(href) ?: return@withLock null
                    val html = resource.use { it.read().getOrNull()?.decodeString()?.getOrNull() }
                        ?: return@withLock null
                    SourceSentenceResolver(html).also { sourceResolver = href.toString() to it }
                }
                resolver.resolve(candidates, anchor)
            }
        }
        return index?.let { book.sentences.getOrNull(it) }
    }

    private fun Locator.toAnchor(index: Int) = SentenceLocatorMatcher.Anchor(
        index = index,
        selector = locations["cssSelector"] as? String,
        before = text.before.orEmpty(),
        text = text.highlight.orEmpty(),
        after = text.after.orEmpty(),
    )

    private fun playFromVisibleParagraph() = playFromReadingLocation(null)

    /** 等待共享听书索引后，从目录目标或当前可见段落起播；查询完成前重验最新导航意图。 */
    private fun playFromReadingLocation(requested: Locator? = requestedReadingStart) {
        val fragment = navigator ?: return
        readingPlaybackIntent = ReadingPlaybackIntent.FROM_READING_LOCATION
        resumableProgress = null
        pendingStoppedProgress = null
        val revision = readingNavigationRevision
        readingStartJob?.cancel()
        readingStartJob = lifecycleScope.launch {
            try {
                if (speechBook == null) toast("正在准备听书…")
                awaitSpeechBook()
                if (revision != readingNavigationRevision || navigator !== fragment) return@launch
                val sentence = if (requested != null) {
                    val id = requested.locations.fragments.firstOrNull()
                    val anchor = if (id != null) SourceSpeechAnchor.Fragment(id) else SourceSpeechAnchor.ResourceStart
                    resolveSourceSentence(requested, anchor)
                } else {
                    val page = fragment.currentLocator.value
                    freshVisibleTextAnchor(fragment, page)?.let(::sentenceAt)
                }
                if (revision != readingNavigationRevision) return@launch
                if (sentence == null) {
                    toast("当前位置暂无可朗读文字，请选择有正文的章节或长按文字开始听书。")
                } else {
                    startSentence(sentence)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                toast(failure.message ?: "听书暂时无法准备，请重试。")
            }
        }
    }

    /** 将选中文字等精确文本定位转换为句子起播，无法匹配时保留当前音频不擅自跳转。 */
    private fun playFromLocator(locator: Locator) {
        resumableProgress = null
        pendingStoppedProgress = null
        val sentence = sentenceAt(locator)
        if (sentence == null) {
            toast("当前位置暂无可朗读文字，请从目录选择正文。")
            return
        }
        startSentence(sentence)
    }

    private fun startSentence(sentence: SpeechSentence) {
        requestAudioStart(ReadingProgress(bookId, sentence.locatorJson, sentence.index))
    }

    /** 先登记唯一请求与等待游标，再发起服务命令，使同步失败和异步确认都关联正确操作。 */
    private fun requestAudioStart(progress: ReadingProgress) {
        // startService 可能同步发布状态或失败，因此发送命令之前先建立请求关联。
        val requestId = PlaybackHub.newPlaybackSessionId()
        resumableProgress = null
        pendingStoppedProgress = null
        pendingAudioSentence = progress.sentenceIndex
        pendingAudioRequestId = requestId
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        followAudio = true
        requestedReadingStart = null
        if (!PlaybackHub.playBook(this, bookId, progress.sentenceIndex, progress.audioOffsetMs, requestId)) {
            audioStartFailed(progress)
        }
    }

    /** 起播失败后保存目标及句内偏移，让重试沿用用户刚选的位置而非旧音频游标。 */
    private fun audioStartFailed(progress: ReadingProgress) {
        clearPendingAudioStart()
        readingPlaybackIntent = ReadingPlaybackIntent.RETRY_AUDIO_START
        resumableProgress = progress
        pendingStoppedProgress = progress
    }

    /** 有停止或失败游标时优先恢复；否则从阅读页起播，索引未就绪时复用现有加载任务。 */
    private fun resumeOrPlayFromPage() {
        val saved = resumableProgress
        if (saved == null) {
            playFromReadingLocation()
        } else if (speechBook == null && navigator != null) {
            val revision = readingNavigationRevision
            readingStartJob?.cancel()
            readingStartJob = lifecycleScope.launch {
                try {
                    toast("正在准备听书…")
                    awaitSpeechBook()
                    if (revision != readingNavigationRevision || resumableProgress == null) return@launch
                    val ready = resumableProgress ?: saved
                    resumableProgress = null
                    pendingStoppedProgress = null
                    requestAudioStart(ready)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    toast(failure.message ?: "听书暂时无法准备，请重试。")
                }
            }
        } else {
            resumableProgress = null
            pendingStoppedProgress = null
            requestAudioStart(saved)
        }
    }

    /** 在系统选文菜单增加起播入口，查询与索引加载期间继续校验用户导航代次。 */
    private val selectionMenu = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            markManualNavigation()
            menu.add(Menu.NONE, SELECTION_LISTEN_ID, 0, "从这里听")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SELECTION_LISTEN_ID) return false
            readingStartJob?.cancel()
            val revision = readingNavigationRevision
            val fragment = navigator ?: return true
            readingStartJob = lifecycleScope.launch {
                try {
                    val locator = queryNavigatorLocator { fragment.currentSelection()?.locator }
                        ?: visibleTextAnchor(fragment, fragment.currentLocator.value)
                    if (revision == readingNavigationRevision && locator != null) {
                        if (speechBook == null) toast("正在准备听书…")
                        awaitSpeechBook()
                        if (revision == readingNavigationRevision && navigator === fragment) playFromLocator(locator)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    toast(failure.message ?: "听书暂时无法准备，请重试。")
                } finally {
                    if (fragment.isAdded && fragment.view != null) fragment.clearSelection()
                    mode.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private fun epubPreferences() = EpubPreferences(
        fontSize = fontScale.toDouble(),
        fontFamily = ReadiumFontFamily.SERIF,
        lineHeight = 1.8,
        pageMargins = 1.2,
        publisherStyles = false,
        columnCount = ColumnCount.ONE,
        scroll = scroll,
        theme = if (pageStyle == "dark") Theme.DARK else Theme.LIGHT,
        backgroundColor = ReadiumColor(paper.toArgb()),
        textColor = ReadiumColor(ink.toArgb()),
    )

    /** 更新阅读偏好并异步落盘，再提交给 Readium，同时更新叠加界面和系统栏主题。 */
    private fun updateAppearance() {
        preferences.edit()
            .putFloat("font_scale", fontScale)
            .putString("page_style", pageStyle)
            .putBoolean("scroll", scroll)
            .apply()
        navigator?.submitPreferences(epubPreferences())
        root.setBackgroundColor(paper.toArgb())
        updateSystemBars()
    }

    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = pageStyle != "dark"
            isAppearanceLightNavigationBars = pageStyle != "dark"
        }
    }

    /** 保存视觉位置、跟随偏好与未完成起播关联，供配置变化后恢复同一阅读意图。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LOCATOR, navigator?.currentLocator?.value?.toJSON()?.toString())
        outState.putBoolean(STATE_FOLLOW, followAudio)
        outState.putString(STATE_FOLLOW_REQUEST, handledFollowRequestId)
        outState.putString(STATE_PLAYBACK_INTENT, readingPlaybackIntent.name)
        outState.putString(STATE_READING_START, requestedReadingStart?.toJSON()?.toString())
        pendingAudioSentence?.let { outState.putInt(STATE_PENDING_AUDIO_SENTENCE, it) }
            ?: outState.remove(STATE_PENDING_AUDIO_SENTENCE)
        outState.putString(STATE_PENDING_AUDIO_REQUEST, pendingAudioRequestId)
        outState.putBoolean(STATE_READING_CONTROLS, showReadingControls)
        outState.putBoolean(STATE_LISTENING, showListening)
        pendingStoppedProgress?.let { progress ->
            outState.putBundle(STATE_STOPPED_PROGRESS, Bundle().apply {
                putString("book_id", progress.bookId)
                putString("locator", progress.locatorJson)
                putInt("sentence", progress.sentenceIndex)
                putLong("offset", progress.audioOffsetMs)
                putLong("updated", progress.updatedAt)
            })
        }
        super.onSaveInstanceState(outState)
    }

    /** 在 WebView 可用时捕获最终位置；保存任务独立完成，并登记到书库以协调快速重开。 */
    override fun onPause() {
        navigator?.takeIf { canSaveReadingLocation() }?.let { fragment ->
            val page = fragment.currentLocator.value
            // 趁 WebView 仍可用先解析可见段落，再独立完成最终 AtomicFile 写入，
            // 不让 Activity 的取消中断进度保存。
            val saveJob = lifecycleScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
                try {
                    saveReadingLocation(fragment, page, finalSave = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // 可选听书索引损坏不能导致仍可阅读的 EPUB 页面崩溃。
                    Log.w("FreeReadingReader", "Could not save the reading position", failure)
                }
            }
            readingRecord?.let { repository.registerReadingSave(bookId, it.addedAt, saveJob) }
            saveJob.start()
        }
        super.onPause()
    }

    /** 先断开音频观察并移除旧导航 Fragment，再释放其持有的 Publication。 */
    override fun onDestroy() {
        audioBinding?.cancel()
        audioHighlighter = null
        navigator?.takeIf { it.isAdded && !supportFragmentManager.isDestroyed }?.let { fragment ->
            // 恢复时会重开文件，因此旋转屏幕也要在关闭 Publication 前
            // 丢弃旧 Fragment 及其 ViewModel。
            supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        super.onDestroy()
        publication?.close()
        publication = null
    }

    /** 将当前书籍与音频状态映射为叠加菜单及独立听书弹层，关闭弹层不停止服务。 */
    @Composable
    private fun ReaderSurfaces() {
        val audio by PlaybackHub.state.collectAsStateWithLifecycle()
        val voices by PlaybackHub.voiceState.collectAsStateWithLifecycle()
        val libraryBooks by repository.books.collectAsStateWithLifecycle()
        val activeBook = audio.bookId == bookId
        val book = readingRecord ?: speechBook?.book
        val voiceLanguage = book?.language
            ?: libraryBooks.firstOrNull { it.id == bookId }?.language.orEmpty()
        val title = book?.title ?: audio.bookTitle.takeIf { activeBook && it.isNotBlank() } ?: "正在打开书籍"
        val enabled = navigator != null && !loading
        BackHandler(enabled = showReadingControls && !showListening && !showContents && !showAppearance) {
            showReadingControls = false
        }
        ReaderChrome(
            title = title,
            chapter = visibleLocator?.title?.takeIf(String::isNotBlank) ?: "轻触正文打开阅读菜单",
            readingProgress = visibleLocator?.locations?.totalProgression?.toFloat(),
            controlsVisible = showReadingControls,
            hasAudio = activeBook,
            isPlaying = activeBook && audio.isPlaying,
            isPreparing = activeBook && audio.isPreparing,
            enabled = enabled,
            paper = paper, ink = ink, accent = accent,
            onBack = ::finish,
            onContents = ::openContents,
            onAppearance = { showReadingControls = false; showAppearance = true },
            onListen = { showReadingControls = false; showListening = true },
            onToggleControls = { showReadingControls = !showReadingControls },
            followingAudio = followingAudio,
            onFollowToggle = ::toggleAudioFollow,
        )
        if (showContents) ContentsDialog()
        if (showAppearance) AppearanceDialog()
        if (showListening) {
            Dialog(
                onDismissRequest = { showListening = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                ListeningPlayerScreen(
                    bookTitle = title, author = book?.author.orEmpty(), coverPath = book?.coverPath,
                    snapshot = audio, activeBook = activeBook, enabled = enabled, followAudio = followingAudio,
                    paper = paper, ink = ink, accent = accent,
                    onClose = { showListening = false },
                    onToggle = ::toggleListening,
                    onSeek = { PlaybackHub.seekBy(this, it) },
                    onSpeed = { PlaybackHub.setSpeed(this, it) },
                    onFromReading = ::playFromVisibleParagraph,
                    onFollowToggle = ::toggleAudioFollow,
                    onLocate = { showListening = false; showReadingControls = false; followCurrentAudio() },
                    onStop = ::stopListening,
                    onVoiceSettings = { PlaybackHub.openVoiceSettings(this) },
                    voiceState = voices.takeIf { it.bookId == bookId && it.language == voiceLanguage }
                        ?: TtsVoiceUiState(),
                    onLoadVoices = { PlaybackHub.loadVoices(this, bookId, voiceLanguage) },
                    onSelectVoice = { PlaybackHub.selectVoice(this, bookId, voiceLanguage, it) },
                    onPreviousChapter = { skipListeningChapter(forward = false) },
                    onNextChapter = { skipListeningChapter(forward = true) },
                )
            }
        }
    }

    /** 仅对本书有效的相邻章节发送命令，目标由服务确认后再开启跟随，避免先跳回旧句。 */
    private fun skipListeningChapter(forward: Boolean) {
        val audio = PlaybackHub.state.value
        if (audio.bookId != bookId ||
            !(if (forward) audio.canGoToNextChapter else audio.canGoToPreviousChapter)) return
        // 明确选择音频章节后，旧选文或页面定位任务不能再改变播放起点。
        cancelReadingStart()
        resumableProgress = null
        pendingStoppedProgress = null
        requestedReadingStart = null
        if (forward) PlaybackHub.nextChapter(this, bookId) else PlaybackHub.previousChapter(this, bookId)
    }

    /** 主播放操作按最新意图选择暂停、恢复或阅读定位起播；正文重开时已有音频仍可控制。 */
    private fun toggleListening() {
        // 最新的播放控制操作优先于尚未完成的 DOM 或选文定位。
        cancelReadingStart()
        val audio = PlaybackHub.state.value
        if (pendingStoppedProgress == null && (navigator == null || loading) && audio.bookId == bookId) {
            // 页面重建后 Readium 尚在重新打开时，已有音频会话仍可直接控制。
            PlaybackHub.toggle(this)
            return
        }
        if (pendingStoppedProgress != null) {
            if (readingPlaybackIntent == ReadingPlaybackIntent.FROM_READING_LOCATION) {
                playFromReadingLocation()
            } else if (readingPlaybackIntent == ReadingPlaybackIntent.RETRY_AUDIO_START &&
                primaryPlaybackAction(audio, bookId, readingPlaybackIntent) == PrimaryPlaybackAction.PAUSE_OR_TOGGLE) {
                PlaybackHub.toggle(this)
            } else {
                resumeOrPlayFromPage()
            }
            return
        }
        when (primaryPlaybackAction(audio, bookId, readingPlaybackIntent)) {
            PrimaryPlaybackAction.PAUSE_OR_TOGGLE, PrimaryPlaybackAction.RESUME_AUDIO -> PlaybackHub.toggle(this)
            PrimaryPlaybackAction.START_FROM_READING_LOCATION -> playFromReadingLocation()
            PrimaryPlaybackAction.RESUME_SAVED_OR_READING -> resumeOrPlayFromPage()
        }
    }

    /** 先捕获本书句内游标并取消待起播任务，再结束服务，避免旧查询稍后重新播放。 */
    private fun stopListening() {
        cancelReadingStart()
        clearPendingAudioStart()
        showListening = false
        val audio = PlaybackHub.state.value
        if (audio.bookId != bookId) return
        pendingStoppedProgress = stoppedPlaybackProgress(bookId, audio, speechBook?.sentences)
        resumableProgress = pendingStoppedProgress
        PlaybackHub.stop(this)
    }

    @Composable
    private fun OpeningState() {
        if (!loading && openingError == null) return
        Column(
            Modifier.fillMaxSize().background(paper).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(20.dp))
                Text("正在铺开书页…", color = ink, fontFamily = FontFamily.Serif, fontSize = 19.sp)
            } else {
                Text("暂时无法打开这本书", color = ink, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                Text(openingError.orEmpty(), color = muted, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::openBook) { Text("重新尝试") }
                TextButton(onClick = ::finish) { Text("返回书架") }
            }
        }
    }

    /** 以导航器的最新页面打开目录；同资源多锚点时异步解析 DOM，过期弹窗结果不再应用。 */
    private fun openContents() {
        val opened = publication ?: return
        val fragment = navigator ?: return
        // 导航器保存最新页，界面显示的定位经过保存防抖，可能稍有滞后。
        val page = fragment.currentLocator.value
        val entries = flattenContents(opened.tableOfContents).ifEmpty {
            opened.readingOrder.mapIndexed { index, link -> ContentsEntry(link, 0, "第 ${index + 1} 节") }
        }
        val candidates = entries.withIndex().filter { sameContentsResource(it.value, page) }
        val fragments = page.locations.fragments + listOfNotNull(page.href.fragment)
        val hasExactFragment = candidates.any { contentsFragment(it.value)?.let(fragments::contains) == true }
        val needsElement = !hasExactFragment && candidates.size > 1 &&
            candidates.any { contentsFragment(it.value) != null }
        // 父项与子项可能共用锚点，仅保留嵌套最深的别名参与边界判断。
        val boundaries = candidates.groupBy { contentsFragment(it.value) }.values.map { aliases ->
            val entry = aliases.maxBy { it.value.depth }
            entry.index to contentsFragment(entry.value)
        }
        val fallback = currentContentsIndex(entries, page)
        val token = ++contentsGeneration
        contentsJob?.cancel()
        contentsEntries = entries
        selectedContentsIndex = if (needsElement) null else fallback
        showReadingControls = false
        showContents = true
        if (!needsElement) return
        contentsJob = lifecycleScope.launch {
            val resolved = try {
                val fresh = queryNavigatorLocator { fragment.firstVisibleElementLocator() }
                if (fresh == null || fragment.currentLocator.value != page ||
                    fresh.href.removeFragment().normalize() != page.href.removeFragment().normalize()) null
                else resolveContentsAt(opened, fresh, boundaries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (showContents && contentsGeneration == token && publication === opened) {
                selectedContentsIndex = resolved ?: fallback
            }
        }
    }

    /** 在原始 XHTML 中用可见元素判断当前目录边界，与句子定位共享互斥的资源解析缓存。 */
    private suspend fun resolveContentsAt(opened: Publication, locator: Locator,
                                         candidates: List<Pair<Int, String?>>): Int? {
        val anchor = (locator.locations["cssSelector"] as? String)?.takeIf(String::isNotBlank)
            ?.let { SourceSpeechAnchor.Element(it) }
            ?: locator.locations.fragments.firstOrNull()?.let { SourceSpeechAnchor.Fragment(it) }
            ?: return null
        val href = locator.href.removeFragment()
        return withContext(Dispatchers.IO) {
            sourceResolverMutex.withLock {
                val resolver = sourceResolver?.takeIf { it.first == href.toString() }?.second ?: run {
                    val resource = opened.get(href) ?: return@withLock null
                    val html = resource.use { it.read().getOrNull()?.decodeString()?.getOrNull() }
                        ?: return@withLock null
                    SourceSentenceResolver(html).also { sourceResolver = href.toString() to it }
                }
                resolver.resolveContents(candidates, anchor)
            }
        }
    }

    /** 关闭目录并推进查询代次，防止迟到的解析结果影响下次打开。 */
    private fun dismissContents() {
        contentsGeneration += 1
        contentsJob?.cancel()
        contentsJob = null
        showContents = false
    }

    @Composable
    private fun ContentsDialog() {
        ReaderContentsDialog(
            entries = contentsEntries, currentIndex = selectedContentsIndex,
            paper = paper, ink = ink, accent = accent,
            onDismiss = ::dismissContents,
            onSelect = { entry ->
                dismissContents()
                markManualNavigation()
                requestedReadingStart = publication?.locatorFromLink(entry.link)
                navigator?.go(entry.link)
            },
        )
    }

    @Composable
    private fun AppearanceDialog() {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            containerColor = paper,
            title = { Text("阅读设置", color = ink, fontFamily = FontFamily.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("字号", color = muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fontScale = (fontScale - 0.1f).coerceAtLeast(0.8f); updateAppearance() }) {
                            Icon(Icons.Default.Remove, "缩小字号", tint = ink)
                        }
                        Text("${(fontScale * 100).toInt()}%", color = ink, fontSize = 21.sp, fontFamily = FontFamily.Serif)
                        IconButton(onClick = { fontScale = (fontScale + 0.1f).coerceAtMost(2f); updateAppearance() }) {
                            Icon(Icons.Default.Add, "放大字号", tint = ink)
                        }
                    }
                    Text("书页", color = muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("paper" to "纸色", "white" to "明亮", "dark" to "夜读").forEach { (style, label) ->
                            FilterChip(
                                selected = pageStyle == style,
                                onClick = { pageStyle = style; updateAppearance() },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("连续滚动", color = ink, fontSize = 14.sp)
                            Text("关闭时左右翻页", color = muted, fontSize = 11.sp)
                        }
                        Switch(checked = scroll, onCheckedChange = { scroll = it; updateAppearance() })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text("完成", color = accent) } },
        )
    }

    private fun parseLocator(json: String): Locator? = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_OPEN_PLAYER = "open_player"
        private const val STATE_LOCATOR = "reader_locator"
        private const val STATE_FOLLOW = "reader_follow_audio"
        private const val STATE_FOLLOW_REQUEST = "reader_follow_request"
        private const val STATE_PLAYBACK_INTENT = "reader_playback_intent"
        private const val STATE_READING_START = "reader_requested_start"
        private const val STATE_PENDING_AUDIO_SENTENCE = "reader_pending_audio_sentence"
        private const val STATE_PENDING_AUDIO_REQUEST = "reader_pending_audio_request"
        private const val STATE_READING_CONTROLS = "reader_controls_visible"
        private const val STATE_LISTENING = "reader_listening_visible"
        private const val STATE_STOPPED_PROGRESS = "reader_stopped_progress"
        private const val SELECTION_LISTEN_ID = 0x46524545

        /** 构造指定书籍的阅读入口，可直接展开独立听书页。 */
        fun intent(context: Context, bookId: String, openPlayer: Boolean = false): Intent = Intent(context, ReaderActivity::class.java)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_OPEN_PLAYER, openPlayer)
    }
}
