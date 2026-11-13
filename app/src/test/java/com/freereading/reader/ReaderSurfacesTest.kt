package com.freereading.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.freereading.audio.PlaybackSnapshot
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import com.freereading.ui.Ink
import com.freereading.ui.Paper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the production Compose surfaces through their accessibility actions on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSurfacesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultReadingChromeStaysCollapsedAndOnlyOffersAListeningShortcut() {
        val events = showChrome(hasAudio = true)

        compose.onNodeWithTag("reader_chrome_footer").assertIsDisplayed()
        compose.onNodeWithTag("reader_chrome_toolbar").assertDoesNotExist()
        compose.onNodeWithTag("reader_chrome_menu").assertDoesNotExist()
        compose.onNodeWithTag("reader_chrome").assert(hasClickAction().not())
        compose.onNodeWithContentDescription("显示阅读工具").assertIsDisplayed()
        assertNoFullPlayerControls()
        compose.onNodeWithTag("reader_listen_shortcut").performClick()

        compose.runOnIdle { assertEquals(listOf("listen"), events) }
    }

    @Test fun readingMenuExpandsAndRoutesToListeningWithoutEmbeddingPlaybackControls() {
        val events = showChrome()

        compose.onNodeWithTag("reader_controls_toggle").performClick()
        compose.onNodeWithTag("reader_chrome_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("reader_chrome_menu").assertIsDisplayed()
        compose.onNodeWithTag("reader_listen_shortcut").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起阅读工具").assertIsDisplayed()
        assertNoFullPlayerControls()
        compose.onNodeWithContentDescription("打开目录").performClick()
        compose.onNodeWithContentDescription("打开阅读设置").performClick()
        compose.onNodeWithContentDescription("打开听书播放器").performClick()
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        compose.onNodeWithTag("reader_chrome_menu").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(listOf("toggle-tools", "contents", "appearance", "listen", "back", "toggle-tools"), events)
        }
    }

    @Test fun readingMenuDisablesUnavailableActionsWhileLeavingBackAvailable() {
        showChrome(controlsVisible = true, enabled = false)

        listOf("reader_controls_toggle", "reader_contents", "reader_appearance", "reader_listen")
            .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithContentDescription("返回书架").assertIsEnabled()
    }

    @Test fun collapsedChromeKeepsActiveListeningShortcutAvailableWhileReaderLoads() {
        val events = showChrome(hasAudio = true, enabled = false)

        compose.onNodeWithTag("reader_controls_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("reader_chrome_menu").assertDoesNotExist()
        compose.onNodeWithTag("reader_listen_shortcut").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("listen"), events) }
    }

    @Test fun expandedChromeKeepsActiveListeningAvailableWhileReadingActionsAreDisabled() {
        val events = showChrome(controlsVisible = true, hasAudio = true, enabled = false)

        listOf("reader_controls_toggle", "reader_contents", "reader_appearance")
            .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithTag("reader_listen_shortcut").assertDoesNotExist()
        compose.onNodeWithTag("reader_listen").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("listen"), events) }
    }

    @Test fun readingFooterDoesNotConsumeTouchesAboveItsVisibleBounds() {
        val events = showChrome()
        val root = compose.onNodeWithTag("reader_chrome").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("reader_chrome_footer").fetchSemanticsNode().boundsInRoot
        val outsideDistance = 5f * compose.density.density

        compose.onNodeWithTag("reader_chrome").performTouchInput {
            click(Offset(root.width / 2f, footer.top - root.top - outsideDistance))
        }

        compose.onNodeWithTag("reader_chrome_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithTag("reader_chrome").performTouchInput {
            click(Offset(root.width / 2f, footer.center.y - root.top))
        }
        compose.runOnIdle { assertEquals(listOf("toggle-tools"), events) }
    }

    @Test fun listeningShortcutDoesNotConsumeTouchesAboveItsVisibleBounds() {
        val events = showChrome(hasAudio = true)
        val root = compose.onNodeWithTag("reader_chrome").fetchSemanticsNode().boundsInRoot
        val shortcut = compose.onNodeWithTag("reader_listen_shortcut").fetchSemanticsNode().boundsInRoot
        val outsideDistance = 5f * compose.density.density

        compose.onNodeWithTag("reader_chrome").performTouchInput {
            click(Offset(shortcut.center.x - root.left, shortcut.top - root.top - outsideDistance))
        }

        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithTag("reader_chrome").performTouchInput {
            click(Offset(shortcut.center.x - root.left, shortcut.center.y - root.top))
        }
        compose.runOnIdle { assertEquals(listOf("listen"), events) }
    }

    @Test fun closingListeningReturnsToReadingWithoutStoppingThePlayingBook() {
        val events = showListening()

        compose.onNodeWithTag("listening-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回阅读").performClick()

        compose.runOnIdle { assertEquals(listOf("close"), events) }
    }

    @Test fun listeningControlsDispatchTheirActualValuesAndIndependentActions() {
        val events = showListening()

        clickListening("暂停听书")
        clickListening("快退15秒")
        clickListening("快进15秒")
        clickListening("播放速度")
        compose.onNodeWithText("1.5×").performClick()
        clickListening("从阅读位置开始听书")
        compose.onNodeWithContentDescription("跟随原文").performScrollTo().assertIsOn().performClick()
        clickListening("定位正在朗读的原文")
        clickListening("结束听书")

        compose.runOnIdle {
            assertEquals(listOf("toggle", "seek:-15000", "seek:15000", "speed:1.5",
                "from-reading", "follow", "locate", "stop"), events)
        }
    }

    @Test fun inactiveBookOffersStartButCannotControlAnotherBooksAudioOrExposeItsProgress() {
        val events = showListening(activeBook = false)

        compose.onNodeWithText(SpokenText).assertDoesNotExist()
        compose.onNodeWithText("第七章 山间").assertDoesNotExist()
        compose.onNodeWithContentDescription("章节播放进度").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "—"))
        listOf("快退15秒", "快进15秒", "播放速度", "跟随原文", "定位正在朗读的原文", "结束听书")
            .forEach { compose.onNodeWithContentDescription(it).assertIsNotEnabled() }
        clickListening("开始或继续听书")
        clickListening("从阅读位置开始听书")

        compose.runOnIdle { assertEquals(listOf("toggle", "from-reading"), events) }
    }

    @Test fun activeAudioRemainsControllableWhileReaderLoads() {
        val events = showListening(enabled = false, snapshot = sampleSnapshot().copy(
            error = "缺少离线语音数据", needsVoiceData = true))

        listOf("跟随原文", "定位正在朗读的原文", "从阅读位置开始听书")
            .forEach { compose.onNodeWithContentDescription(it).assertIsNotEnabled() }
        clickListening("暂停听书")
        clickListening("快退15秒")
        clickListening("快进15秒")
        clickListening("播放速度")
        compose.onNodeWithText("1.5×").performClick()
        clickListening("安装或设置离线语音")
        clickListening("结束听书")
        compose.onNodeWithContentDescription("返回阅读").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf("toggle", "seek:-15000", "seek:15000", "speed:1.5",
                "voice-settings", "stop", "close"), events)
        }
    }

    @Test fun inactiveBookDisablesAllActionsWhileReaderLoadsButCloseStaysAvailable() {
        val events = showListening(activeBook = false, enabled = false,
            snapshot = sampleSnapshot().copy(error = "其他书的离线语音错误", needsVoiceData = true))

        listOf("开始或继续听书", "快退15秒", "快进15秒", "播放速度", "跟随原文",
            "定位正在朗读的原文", "从阅读位置开始听书", "结束听书")
            .forEach { compose.onNodeWithContentDescription(it).assertIsNotEnabled() }
        compose.onNodeWithContentDescription("安装或设置离线语音").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回阅读").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("close"), events) }
    }

    @Test fun missingOfflineVoiceHasAnActionableSettingsControl() {
        val events = showListening(snapshot = sampleSnapshot().copy(isPlaying = false,
            error = "缺少离线语音数据", needsVoiceData = true))

        compose.onNodeWithText("缺少离线语音数据").performScrollTo().assertIsDisplayed()
        clickListening("安装或设置离线语音")

        compose.runOnIdle { assertEquals(listOf("voice-settings"), events) }
    }

    @Test fun playerDisplaysChapterPercentageRatherThanCurrentSentencePercentage() {
        showListening(snapshot = sampleSnapshot().copy(
            chapterProgress = 0.37f, chapterSentenceCount = 20, positionMs = 5_000, durationMs = 10_000))

        compose.onNodeWithText("37%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("章节播放进度")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "37%"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.37f, 0f..1f)))
        compose.onNodeWithText("50%").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    fun shortLandscapeViewportKeepsCloseAccessibleAndCanScrollToStop() {
        val events = showListening(snapshot = sampleSnapshot().copy(
            bookTitle = "很长的书名，用于检验横屏时内容仍然能够完整滚动", text = SpokenText.repeat(12)))

        compose.onNodeWithTag("listening-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回阅读").assertIsDisplayed()
        compose.onNodeWithContentDescription("结束听书").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("返回阅读").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(listOf("stop", "close"), events) }
    }

    private fun assertNoFullPlayerControls() {
        listOf("暂停听书", "开始或继续听书", "快退15秒", "快进15秒", "播放速度",
            "结束听书", "从阅读位置开始听书", "定位正在朗读的原文")
            .forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithTag("listening-screen").assertDoesNotExist()
    }

    private fun clickListening(description: String) {
        compose.onNodeWithContentDescription(description).performScrollTo().assertIsEnabled().performClick()
    }

    private fun showChrome(
        controlsVisible: Boolean = false,
        hasAudio: Boolean = false,
        enabled: Boolean = true,
    ): MutableList<String> {
        val events = mutableListOf<String>()
        var visible by mutableStateOf(controlsVisible)
        compose.setContent {
            FreeReadingTheme {
                ReaderChrome(title = "山间来信", chapter = "第七章 山间", readingProgress = 0.6f,
                    controlsVisible = visible, hasAudio = hasAudio, isPlaying = true, isPreparing = false,
                    enabled = enabled, paper = Paper, ink = Ink, accent = Forest,
                    onBack = { events += "back" }, onContents = { events += "contents" },
                    onAppearance = { events += "appearance" }, onListen = { events += "listen" },
                    onToggleControls = { visible = !visible; events += "toggle-tools" })
            }
        }
        return events
    }

    private fun showListening(
        snapshot: PlaybackSnapshot = sampleSnapshot(),
        activeBook: Boolean = true,
        enabled: Boolean = true,
    ): MutableList<String> {
        val events = mutableListOf<String>()
        compose.setContent {
            FreeReadingTheme {
                ListeningPlayerScreen(bookTitle = snapshot.bookTitle, author = "示例作者", coverPath = null,
                    snapshot = snapshot, activeBook = activeBook, enabled = enabled, followAudio = true,
                    paper = Paper, ink = Ink, accent = Forest,
                    onClose = { events += "close" }, onToggle = { events += "toggle" },
                    onSeek = { events += "seek:$it" }, onSpeed = { events += "speed:$it" },
                    onFromReading = { events += "from-reading" }, onFollowToggle = { events += "follow" },
                    onLocate = { events += "locate" }, onStop = { events += "stop" },
                    onVoiceSettings = { events += "voice-settings" })
            }
        }
        return events
    }

    private fun sampleSnapshot() = PlaybackSnapshot(bookId = "book-a", bookTitle = "山间来信",
        sentenceIndex = 12, sentenceCount = 80, chapterTitle = "第七章 山间", text = SpokenText,
        positionMs = 5_000, durationMs = 10_000, chapterProgress = 0.37f,
        chapterSentenceCount = 20, isPlaying = true)

    private companion object { const val SpokenText = "沿着山路，我们听见了风穿过树林的声音。" }
}
