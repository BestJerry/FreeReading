@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.freereading.reader

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import com.freereading.audio.progressFraction
import com.freereading.data.ReadingProgress
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import com.freereading.ui.FreeReadingTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import org.readium.r2.navigator.Decoration
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
    private var visibleLocator by mutableStateOf<Locator?>(null)
    private var loading by mutableStateOf(true)
    private var openingError by mutableStateOf<String?>(null)
    private var showContents by mutableStateOf(false)
    private var showAppearance by mutableStateOf(false)
    private var showSpeed by mutableStateOf(false)
    private var followAudio by mutableStateOf(true)
    private var fontScale by mutableStateOf(1.15f)
    private var pageStyle by mutableStateOf("paper")
    private var scroll by mutableStateOf(false)
    private var loadJob: Job? = null
    private lateinit var root: LinearLayout
    private lateinit var documentFrame: FrameLayout
    private lateinit var openingOverlay: ComposeView
    private val fragmentContainerId = View.generateViewId()
    private var restoredLocator: String? = null
    private var resumableProgress: ReadingProgress? = null
    private var lastAnchorChapter: Int? = null
    private var chapterAnchors: List<SentenceLocatorMatcher.Anchor> = emptyList()
    private var readingOrderHrefs: List<String> = emptyList()
    private var readingPlaybackIntent by mutableStateOf(ReadingPlaybackIntent.CONTINUE_AUDIO)
    private var requestedReadingStart: Locator? = null
    private var readingNavigationRevision = 0L
    private var readingStartJob: Job? = null
    private val sourceResolverMutex = Mutex()
    private var sourceResolver: Pair<String, SourceSentenceResolver>? = null
    private data class VisibleTextSnapshot(val page: Locator, val textAnchor: Locator, val revision: Long)
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
        // Readium Fragments retain a Publication supplied by their factory. Rebuild them after
        // reopening the local file instead of restoring a Fragment with a closed/missing book.
        super.onCreate(null)
        fontScale = preferences.getFloat("font_scale", 1.15f).coerceIn(0.8f, 2f)
        pageStyle = preferences.getString("page_style", "paper").orEmpty()
        scroll = preferences.getBoolean("scroll", false)
        buildViews()
        observeAudio()
        openBook()
    }

    private fun buildViews() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper.toArgb())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(composeView { ReaderHeader() }, LinearLayout.LayoutParams(-1, -2))
        val body = FrameLayout(this)
        documentFrame = FrameLayout(this).apply { id = fragmentContainerId }
        body.addView(documentFrame, FrameLayout.LayoutParams(-1, -1))
        openingOverlay = composeView { OpeningState() }
        body.addView(openingOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(composeView { ReaderAudioControls() }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateSystemBars()
    }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { FreeReadingTheme { content() } }
    }

    private fun openBook() {
        loadJob?.cancel()
        loading = true
        openingError = null
        openingOverlay.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            var opened: Publication? = null
            var installed = false
            try {
                require(bookId.isNotEmpty()) { "未找到书籍，请返回书架重新打开。" }
                val book = repository.loadSpeechBook(bookId)
                val saved = withContext(Dispatchers.IO) { repository.progress(bookId) }
                val readyPublication = withContext(Dispatchers.IO) {
                    repository.readium.open(repository.bookFile(bookId)).also { opened = it }
                }
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                    installNavigator(readyPublication, book, saved)
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
        speechBook = book
        publication = opened
        readingOrderHrefs = opened.readingOrder.map { it.url().removeFragment().toString() }
        val audio = PlaybackHub.state.value
        resumableProgress = saved.takeUnless {
            audio.bookId == bookId || readingPlaybackIntent == ReadingPlaybackIntent.FROM_READING_LOCATION
        }
        val initial = when {
            followAudio && audio.bookId == bookId -> book.sentences.getOrNull(audio.sentenceIndex)?.locatorJson
            restoredLocator != null -> restoredLocator
            else -> saved.locatorJson
        }?.let(::parseLocator)
        visibleLocator = initial
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
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionMenu,
                shouldApplyInsetsPadding = false,
            ),
        )
        supportFragmentManager.fragmentFactory = factory
        val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = fragment
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
                    else -> false
                }
            }

            override fun onDrag(event: DragEvent): Boolean {
                if (event.type == DragEvent.Type.Start) markManualNavigation()
                return false
            }
        })
        supportFragmentManager.beginTransaction()
            .replace(fragmentContainerId, fragment, "epub")
            .commitNow()
        loading = false
        openingOverlay.visibility = View.GONE
        observeLocator(fragment)
        synchronizeAudio()
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

    private fun observeAudio() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackHub.state
                    .map { it.bookId to it.sentenceIndex }
                    .distinctUntilChanged()
                    .collect { synchronizeAudio() }
            }
        }
    }

    private fun synchronizeAudio() {
        val fragment = navigator ?: return
        if (fragment.view == null) return
        val audio = PlaybackHub.state.value
        val sentence = speechBook?.sentences?.getOrNull(audio.sentenceIndex)
            ?.takeIf { audio.bookId == bookId }
        lifecycleScope.launch {
            val locator = sentence?.locatorJson?.let(::parseLocator)
            fragment.applyDecorations(
                locator?.let {
                    listOf(Decoration(
                        id = "speech-sentence",
                        locator = it,
                        style = Decoration.Style.Highlight(tint = 0xFFE4CB88.toInt()),
                    ))
                } ?: emptyList(),
                group = "speech",
            )
            if (locator != null && followAudio) fragment.go(locator, animated = false)
        }
    }

    private suspend fun saveReadingLocation(fragment: EpubNavigatorFragment, page: Locator) {
        // While this book owns the audio session, the service alone persists its canonical
        // locator + sentence offset. Browsing the page must not overwrite the listening cursor.
        if (!canSaveReadingLocation()) return
        val textAnchor = visibleTextAnchor(fragment, page) ?: return
        if (fragment.currentLocator.value != page) return
        val progress = readingProgressAt(page, textAnchor) ?: return
        withContext(Dispatchers.IO) {
            repository.saveProgress(progress)
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

    private suspend fun freshVisibleTextAnchor(fragment: EpubNavigatorFragment, page: Locator): Locator? {
        val revision = readingNavigationRevision
        val fresh = queryNavigatorLocator { fragment.firstVisibleElementLocator() }
        if (readingNavigationRevision != revision || fragment.currentLocator.value != page) return null
        if (fresh != null && fresh.href.removeFragment() == page.href.removeFragment()) {
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
        readingStartJob?.cancel()
        readingNavigationRevision += 1
        readingPlaybackIntent = ReadingPlaybackIntent.FROM_READING_LOCATION
        requestedReadingStart = null
        visibleTextSnapshot = null
        followAudio = false
        resumableProgress = null
    }

    private fun followCurrentAudio() {
        readingStartJob?.cancel()
        readingNavigationRevision += 1
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = null
        visibleTextSnapshot = null
        followAudio = true
        synchronizeAudio()
    }

    private fun anchorsFor(locator: Locator): List<SentenceLocatorMatcher.Anchor> {
        val book = speechBook ?: return emptyList()
        val href = locator.href.removeFragment().toString()
        val chapter = readingOrderHrefs.indexOf(href)
        if (chapter < 0) return emptyList()
        if (lastAnchorChapter != chapter) {
            chapterAnchors = book.sentences.asSequence()
                .filter { it.chapterIndex == chapter }
                .mapNotNull { sentence -> parseLocator(sentence.locatorJson)?.toAnchor(sentence.index) }
                .toList()
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
        resumableProgress = null
        val fragment = navigator ?: return
        val revision = readingNavigationRevision
        readingStartJob?.cancel()
        readingStartJob = lifecycleScope.launch {
            try {
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
            } catch (_: Exception) {
                toast("书页文字暂时无法解析，请稍后再试。")
            }
        }
    }

    private fun playFromLocator(locator: Locator) {
        readingStartJob?.cancel()
        resumableProgress = null
        val sentence = sentenceAt(locator)
        if (sentence == null) {
            toast("当前位置暂无可朗读文字，请从目录选择正文。")
            return
        }
        startSentence(sentence)
    }

    private fun startSentence(sentence: SpeechSentence) {
        readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
        requestedReadingStart = null
        followAudio = true
        PlaybackHub.playBook(this, bookId, sentence.index)
    }

    private fun resumeOrPlayFromPage() {
        val saved = resumableProgress
        if (saved == null) {
            playFromReadingLocation()
        } else {
            resumableProgress = null
            readingPlaybackIntent = ReadingPlaybackIntent.CONTINUE_AUDIO
            requestedReadingStart = null
            followAudio = true
            PlaybackHub.playBook(this, bookId, saved.sentenceIndex, saved.audioOffsetMs)
        }
    }

    private val selectionMenu = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, SELECTION_LISTEN_ID, 0, "从这里听")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SELECTION_LISTEN_ID) return false
            lifecycleScope.launch {
                val fragment = navigator ?: return@launch
                val locator = queryNavigatorLocator { fragment.currentSelection()?.locator }
                    ?: visibleTextAnchor(fragment, fragment.currentLocator.value)
                if (locator != null) playFromLocator(locator)
                fragment.clearSelection()
                mode.finish()
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
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        navigator?.takeIf { canSaveReadingLocation() }?.let { fragment ->
            val page = fragment.currentLocator.value
            // Resolve the visible paragraph while the WebView is still available, then perform
            // the final AtomicFile write independently of the Activity's cancellation.
            lifecycleScope.launch(NonCancellable) { saveReadingLocation(fragment, page) }
        }
        super.onPause()
    }

    override fun onDestroy() {
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
    private fun ReaderHeader() {
        Column(Modifier.fillMaxWidth().background(paper)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::finish) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架", tint = ink)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text("FREE READING", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text(
                        speechBook?.book?.title ?: "正在打开书籍",
                        color = ink,
                        fontFamily = FontFamily.Serif,
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { showAppearance = true }, enabled = navigator != null) {
                    Text("Aa", color = ink, fontFamily = FontFamily.Serif, fontSize = 21.sp)
                }
                IconButton(onClick = { showContents = true }, enabled = navigator != null) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "打开目录", tint = ink)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 23.dp, end = 23.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    visibleLocator?.title?.takeIf(String::isNotBlank) ?: "让阅读慢下来",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = muted,
                    fontSize = 11.sp,
                )
                visibleLocator?.locations?.totalProgression?.let {
                    Text("${(it * 100).toInt()}%", color = muted, fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = ink.copy(alpha = 0.08f))
        }
        if (showContents) ContentsDialog()
        if (showAppearance) AppearanceDialog()
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

    @Composable
    private fun ReaderAudioControls() {
        val audio by PlaybackHub.state.collectAsStateWithLifecycle()
        val activeBook = audio.bookId == bookId
        val currentSentence = speechBook?.sentences?.getOrNull(audio.sentenceIndex)?.takeIf { activeBook }
        Column(Modifier.fillMaxWidth().background(paper)) {
            HorizontalDivider(color = ink.copy(alpha = 0.09f))
            Row(
                Modifier.fillMaxWidth().padding(start = 21.dp, end = 13.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    if (activeBook && audio.isPreparing) "正在准备语音…" else if (activeBook) "听到这里" else "听书",
                    color = muted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                if (activeBook) {
                    TextButton(onClick = { if (followAudio) followAudio = false else followCurrentAudio() }) {
                        Text(if (followAudio) "已跟随原文" else "跟随原文", color = accent, fontSize = 11.sp)
                    }
                    IconButton(onClick = ::followCurrentAudio, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MyLocation, "定位正在朗读的原文", tint = accent, modifier = Modifier.size(19.dp))
                    }
                }
            }
            if (currentSentence != null) {
                Text(
                    currentSentence.text,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
                    color = muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { showSpeed = true }, enabled = activeBook) {
                        Text(String.format(Locale.ROOT, "%.2g×", audio.speed), color = if (activeBook) accent else muted)
                    }
                    DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}×") },
                                onClick = { PlaybackHub.setSpeed(this@ReaderActivity, speed); showSpeed = false },
                            )
                        }
                    }
                }
                TextButton(onClick = { PlaybackHub.seekBy(this@ReaderActivity, -15_000) }, enabled = activeBook) {
                    Text("−15s", color = if (activeBook) ink else muted, fontSize = 15.sp)
                }
                FilledIconButton(
                    onClick = {
                        when (primaryPlaybackAction(audio, bookId, readingPlaybackIntent)) {
                            PrimaryPlaybackAction.PAUSE_OR_TOGGLE, PrimaryPlaybackAction.RESUME_AUDIO ->
                                PlaybackHub.toggle(this@ReaderActivity)
                            PrimaryPlaybackAction.START_FROM_READING_LOCATION -> playFromReadingLocation()
                            PrimaryPlaybackAction.RESUME_SAVED_OR_READING -> resumeOrPlayFromPage()
                        }
                    },
                    enabled = navigator != null,
                    modifier = Modifier.size(54.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = paper),
                ) {
                    if (activeBook && audio.isPreparing) {
                        CircularProgressIndicator(color = paper, modifier = Modifier.size(23.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (activeBook && audio.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (activeBook && audio.isPlaying) "暂停听书" else "开始或继续听书",
                            modifier = Modifier.size(29.dp),
                        )
                    }
                }
                TextButton(onClick = { PlaybackHub.seekBy(this@ReaderActivity, 15_000) }, enabled = activeBook) {
                    Text("+15s", color = if (activeBook) ink else muted, fontSize = 15.sp)
                }
                TextButton(onClick = ::playFromVisibleParagraph, enabled = navigator != null) {
                    Text("从此段听", color = accent, fontSize = 11.sp)
                }
            }
            if (activeBook && audio.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { audio.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = accent.copy(alpha = 0.5f),
                    trackColor = ink.copy(alpha = 0.05f),
                )
            }
            if (activeBook && audio.error != null) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text(audio.error.orEmpty(), color = if (pageStyle == "dark") Color(0xFFEAB6A1) else Color(0xFF965742), fontSize = 12.sp)
                    if (audio.needsVoiceData) {
                        TextButton(onClick = { PlaybackHub.openVoiceSettings(this@ReaderActivity) }) {
                            Text("安装或设置离线语音")
                        }
                    }
                }
            }
            if (activeBook) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("长按正文，选择「从这里听」", color = muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        resumableProgress = currentSentence?.let { sentence ->
                            ReadingProgress(bookId, sentence.locatorJson, sentence.index, audio.positionMs)
                        }
                        PlaybackHub.stop(this@ReaderActivity)
                    }) {
                        Text("结束听书", color = muted, fontSize = 10.sp)
                    }
                }
            } else {
                Text(
                    "从当前可见段落开始，长按正文可选择起点",
                    color = muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun ContentsDialog() {
        val entries = flattenContents(publication?.tableOfContents.orEmpty()).ifEmpty {
            publication?.readingOrder.orEmpty().mapIndexed { index, link -> ContentsEntry(link, 0, "第 ${index + 1} 节") }
        }
        AlertDialog(
            onDismissRequest = { showContents = false },
            containerColor = paper,
            title = { Text("目录", color = ink, fontFamily = FontFamily.Serif) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    items(entries) { entry ->
                        TextButton(
                            onClick = {
                                markManualNavigation()
                                requestedReadingStart = publication?.locatorFromLink(entry.link)
                                navigator?.go(entry.link)
                                showContents = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(start = (entry.depth * 14).dp),
                        ) {
                            Text(
                                entry.link.title?.takeIf(String::isNotBlank) ?: entry.fallbackTitle,
                                color = ink,
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContents = false }) { Text("关闭", color = accent) } },
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

    private data class ContentsEntry(val link: Link, val depth: Int, val fallbackTitle: String)

    private fun flattenContents(links: List<Link>, depth: Int = 0): List<ContentsEntry> = links.flatMapIndexed { index, link ->
        listOf(ContentsEntry(link, depth, "第 ${index + 1} 节")) + flattenContents(link.children, depth + 1)
    }

    private fun parseLocator(json: String): Locator? = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val STATE_LOCATOR = "reader_locator"
        private const val STATE_FOLLOW = "reader_follow_audio"
        private const val STATE_PLAYBACK_INTENT = "reader_playback_intent"
        private const val STATE_READING_START = "reader_requested_start"
        private const val SELECTION_LISTEN_ID = 0x46524545

        fun intent(context: Context, bookId: String): Intent = Intent(context, ReaderActivity::class.java)
            .putExtra(EXTRA_BOOK_ID, bookId)
    }
}
