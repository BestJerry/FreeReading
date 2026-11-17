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

/** Reading UI observes the service; the Activity never owns a TTS engine or audio player. */
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
    // User preference; browsing can temporarily detach without turning this preference off.
    private var followAudio by mutableStateOf(true)
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
    private data class VisibleTextSnapshot(val page: Locator, val textAnchor: Locator, val revision: Long)
    private data class AudioCursor(val bookId: String?, val sentenceIndex: Int,
                                   val playing: Boolean, val preparing: Boolean, val sessionId: String?,
                                   val failedStartId: String?)
    private var visibleTextSnapshot: VisibleTextSnapshot? = null

    private val paper: Color get() = when (pageStyle) {
        "dark" -> Color(0xFF1E2422)
        "white" -> Color(0xFFFCFCF9)
        else -> Color(0xFFF7F2E8)
    }
    private val ink: Color get() = if (pageStyle == "dark") Color(0xFFE4E9E3) else Color(0xFF293C35)
    private val muted: Color get() = ink.copy(alpha = 0.62f)
    private val accent: Color get() = if (pageStyle == "dark") Color(0xFFB5CDB5) else Color(0xFF49674F)

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredLocator = savedInstanceState?.getString(STATE_LOCATOR)
        followAudio = savedInstanceState?.getBoolean(STATE_FOLLOW, true) ?: true
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
        // Readium Fragments retain a Publication supplied by their factory. Rebuild them after
        // reopening the local file instead of restoring a Fragment with a closed/missing book.
        super.onCreate(null)
        fontScale = preferences.getFloat("font_scale", 1.15f).coerceIn(0.8f, 2f)
        pageStyle = preferences.getString("page_style", "paper").orEmpty()
        scroll = preferences.getBoolean("scroll", false)
        buildViews()
        openBook()
    }

    private fun buildViews() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        documentFrame = FrameLayout(this).apply {
            id = fragmentContainerId
            // Reserve only the thin reading footer, in both reading/menu states.
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
            followingAudio && audio.bookId == bookId ->
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
                    // Readium can cache a decoration while dropping its script before isLoaded.
                    // A new render generation forces a real DOM update once the page is ready.
                    audioHighlighter?.onPageLoaded()
                    // Index decoding is deliberately outside the path to the first readable page.
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
            override fun onTap(event: TapEvent): Boolean {
                val width = fragment.view?.width ?: return false
                return when {
                    event.point.x < width * 0.22f -> {
                        markManualNavigation()
                        fragment.goBackward(animated = true)
                    }
                    event.point.x > width * 0.78f -> {
                        markManualNavigation()
                        fragment.goForward(animated = true)
                    }
                    else -> {
                        showReadingControls = !showReadingControls
                        true
                    }
                }
            }

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

    /** Completes listening capabilities without replacing the already visible reader. */
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
                    // The DOM anchor was captured during loading; the cursor need not change
                    // again before it can now be mapped to the completed listening index.
                    saveReadingLocation(fragment, snapshot.page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("FreeReadingReader", "Could not save the reading position", failure)
                }
            }
        }
    }

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
                            // Starting/resuming the same sentence must also catch up the page.
                            .map { AudioCursor(it.bookId, it.sentenceIndex, it.isPlaying, it.isPreparing,
                                it.playbackSessionId, it.startFailure?.requestId) }
                            .distinctUntilChanged()
                            .collect { synchronizeAudio() }
                    } finally {
                        if (audioHighlighter === synchronizer) audioHighlighter = null
                    }
                }
            }
        }
    }

    private fun synchronizeAudio() {
        val synchronizer = audioHighlighter ?: return
        val audio = PlaybackHub.state.value
        acknowledgeAudioStart(audio)
        val sentence = speechBook?.sentences?.getOrNull(audio.sentenceIndex)
            ?.takeIf { audio.bookId == bookId }
        val locator = sentence?.locatorJson?.let(::parseLocator)
        val target = locator?.let { AudioHighlightSynchronizer.Target(bookId, audio.sentenceIndex, it) }
        synchronizer.request(target, navigate = followingAudio)
    }

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
            // A hidden/recreated reader can miss the requested sentence and return at the next.
            clearPendingAudioStart()
        }
    }

    private suspend fun saveReadingLocation(fragment: EpubNavigatorFragment, page: Locator,
                                           finalSave: Boolean = false) {
        // While this book owns the audio session, the service alone persists its canonical
        // locator + sentence offset. Browsing the page must not overwrite the listening cursor.
        if (!canSaveReadingLocation()) return
        val expectedAddedAt = readingRecord?.addedAt ?: return
        if (speechBook == null) {
            // Capture the DOM evidence while the WebView is alive; index decoding can finish
            // later, including after the Activity's own deferred load has been cancelled.
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

    /** Final saves can outlive the Activity Publication, so reopen only for an unresolved DOM anchor. */
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

    private fun readingProgressAt(page: Locator, textAnchor: Locator): ReadingProgress? {
        if (!canSaveReadingLocation()) return null
        val sentence = sentenceAt(textAnchor) ?: return null
        // The visual locator restores the exact page. The independent text anchor resolves
        // the first visible paragraph to a speech sentence, with no percentage conversion.
        return ReadingProgress(bookId, page.toJSON().toString(), sentence.index)
    }

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
        // During teardown the WebView may already be gone. Reuse only an anchor measured
        // for this exact visual page; never map page progression to text progression.
        return visibleTextSnapshot?.takeIf {
            it.page == page && it.revision == readingNavigationRevision
        }?.textAnchor
    }

    private suspend fun queryNavigatorLocator(query: suspend () -> Locator?): Locator? =
        withTimeoutOrNull(800) {
            // Readium 3.1.2 uses suspendCoroutine around evaluateJavascript. Await its request
            // through a cancellable continuation so teardown cannot retain our final save job.
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

    private fun markManualNavigation() {
        showReadingControls = false
        cancelReadingStart()
        clearPendingAudioStart()
        readingPlaybackIntent = ReadingPlaybackIntent.FROM_READING_LOCATION
        requestedReadingStart = null
        resumableProgress = null
        pendingStoppedProgress = null
    }

    private fun followCurrentAudio() {
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

    private fun toggleAudioFollow() {
        if (followingAudio) {
            followAudio = false
            synchronizeAudio()
        } else {
            followCurrentAudio()
        }
    }

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

    private suspend fun resolveSourceSentence(locator: Locator, anchor: SourceSpeechAnchor): SpeechSentence? {
        val opened = publication ?: return null
        val book = speechBook ?: return null
        val candidates = anchorsFor(locator)
        if (candidates.isEmpty()) return null
        // ResourceStart is an explicit table-of-contents request, never a fallback for a bad anchor.
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

    private fun requestAudioStart(progress: ReadingProgress) {
        // Correlate the queued command before startService can publish or fail synchronously.
        val requestId = PlaybackHub.newPlaybackSessionId()
        resumableProgress = null
        pendingStoppedProgress = null
        pendingAudioSentence = progress.sentenceIndex
        pendingAudioRequestId = requestId
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = null
        if (!PlaybackHub.playBook(this, bookId, progress.sentenceIndex, progress.audioOffsetMs, requestId)) {
            audioStartFailed(progress)
        }
    }

    private fun audioStartFailed(progress: ReadingProgress) {
        clearPendingAudioStart()
        readingPlaybackIntent = ReadingPlaybackIntent.RETRY_AUDIO_START
        resumableProgress = progress
        pendingStoppedProgress = progress
    }

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LOCATOR, navigator?.currentLocator?.value?.toJSON()?.toString())
        outState.putBoolean(STATE_FOLLOW, followAudio)
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

    override fun onPause() {
        navigator?.takeIf { canSaveReadingLocation() }?.let { fragment ->
            val page = fragment.currentLocator.value
            // Resolve the visible paragraph while the WebView is still available, then perform
            // the final AtomicFile write independently of the Activity's cancellation.
            val saveJob = lifecycleScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
                try {
                    saveReadingLocation(fragment, page, finalSave = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A damaged optional listening index must not crash an otherwise readable EPUB.
                    Log.w("FreeReadingReader", "Could not save the reading position", failure)
                }
            }
            readingRecord?.let { repository.registerReadingSave(bookId, it.addedAt, saveJob) }
            saveJob.start()
        }
        super.onPause()
    }

    override fun onDestroy() {
        audioBinding?.cancel()
        audioHighlighter = null
        navigator?.takeIf { it.isAdded && !supportFragmentManager.isDestroyed }?.let { fragment ->
            // Restoration is handled by reopening the file, so discard the old Fragment's
            // ViewModels even on rotation before releasing its Publication.
            supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        super.onDestroy()
        publication?.close()
        publication = null
    }

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
            chapter = visibleLocator?.title?.takeIf(String::isNotBlank) ?: "点按中央打开阅读菜单",
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
                )
            }
        }
    }

    private fun toggleListening() {
        // The newest transport action supersedes a pending DOM/selection lookup.
        cancelReadingStart()
        val audio = PlaybackHub.state.value
        if (pendingStoppedProgress == null && (navigator == null || loading) && audio.bookId == bookId) {
            // Existing audio remains usable while Readium reopens after recreation.
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

    private fun openContents() {
        val opened = publication ?: return
        val fragment = navigator ?: return
        // The navigator has the latest page; the displayed locator is debounced for saving.
        val page = fragment.currentLocator.value
        val entries = flattenContents(opened.tableOfContents).ifEmpty {
            opened.readingOrder.mapIndexed { index, link -> ContentsEntry(link, 0, "第 ${index + 1} 节") }
        }
        val candidates = entries.withIndex().filter { sameContentsResource(it.value, page) }
        val fragments = page.locations.fragments + listOfNotNull(page.href.fragment)
        val hasExactFragment = candidates.any { contentsFragment(it.value)?.let(fragments::contains) == true }
        val needsElement = !hasExactFragment && candidates.size > 1 &&
            candidates.any { contentsFragment(it.value) != null }
        // A parent and a nested entry can share an anchor; keep the most specific alias.
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
        private const val STATE_PLAYBACK_INTENT = "reader_playback_intent"
        private const val STATE_READING_START = "reader_requested_start"
        private const val STATE_PENDING_AUDIO_SENTENCE = "reader_pending_audio_sentence"
        private const val STATE_PENDING_AUDIO_REQUEST = "reader_pending_audio_request"
        private const val STATE_READING_CONTROLS = "reader_controls_visible"
        private const val STATE_LISTENING = "reader_listening_visible"
        private const val STATE_STOPPED_PROGRESS = "reader_stopped_progress"
        private const val SELECTION_LISTEN_ID = 0x46524545

        fun intent(context: Context, bookId: String, openPlayer: Boolean = false): Intent = Intent(context, ReaderActivity::class.java)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_OPEN_PLAYER, openPlayer)
    }
}
