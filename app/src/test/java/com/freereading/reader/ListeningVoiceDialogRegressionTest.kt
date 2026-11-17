package com.freereading.reader

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.TtsVoiceCatalog
import com.freereading.audio.TtsVoiceOption
import com.freereading.audio.TtsVoiceSelection
import com.freereading.audio.TtsVoiceUiState
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import com.freereading.ui.Ink
import com.freereading.ui.Paper
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recreates the two-window production hierarchy and asynchronous catalog arrival. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningVoiceDialogRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedListeningDialogSurvivesLoadingAndCatalogArrivalWithUniqueIds() {
        val fixture = showNestedPlayer()
        openAndFinishCatalog(fixture, voices(repeatName = false))

        compose.onNodeWithTag("tts-voice-dialog").assertIsDisplayed()
        compose.onNodeWithText("installed-local-1").assertIsDisplayed()
        closePickerAndAssertPlayerRemains(fixture)
    }

    @Test fun nestedListeningDialogSurvivesCatalogWithRepeatedEngineVoiceIds() {
        val fixture = showNestedPlayer()
        // Voice equality can distinguish locale/quality even if an engine reuses a name.
        // Production IDs are engine + name, so these three entries collide as LazyColumn keys.
        openAndFinishCatalog(fixture, voices(repeatName = true))

        compose.onNodeWithTag("tts-voice-dialog").assertIsDisplayed()
        val voiceId = TtsVoiceSelection.voiceId("local.engine", "installed-local")
        compose.onAllNodesWithTag("tts-voice-$voiceId").assertCountEquals(1)
        compose.onNodeWithTag("tts-voice-$voiceId").assertIsEnabled().performClick()
        closePickerAndAssertPlayerRemains(fixture, listOf("load", "select:$voiceId"))
    }

    private fun openAndFinishCatalog(fixture: Fixture, voices: List<TtsVoiceOption>) {
        compose.onNodeWithContentDescription("选择朗读音色").performScrollTo().performClick()
        compose.onNodeWithTag("tts-voice-dialog").assertIsDisplayed()
        compose.onNodeWithText("正在加载离线音色…").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.playerVisible.value)
            assertEquals(listOf("load"), fixture.events)
            fixture.state.value = fixture.state.value.copy(loading = false,
                catalog = TtsVoiceCatalog("local.engine", voices, null, voices.first().id))
        }
        // The real failure, if any, arrives on the subsequent list composition/layout pass.
        compose.waitForIdle()
    }

    private fun closePickerAndAssertPlayerRemains(fixture: Fixture, expectedEvents: List<String> = listOf("load")) {
        compose.onNodeWithTag("listening-screen").assertExists()
        compose.onNodeWithTag("tts-voice-done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tts-voice-dialog").assertDoesNotExist()
        compose.onNodeWithTag("listening-screen").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.playerVisible.value)
            assertEquals(expectedEvents, fixture.events)
        }
    }

    private fun showNestedPlayer(): Fixture {
        val state = mutableStateOf(TtsVoiceUiState(bookId = "book-a", language = "zh-CN"))
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent {
            FreeReadingTheme {
                if (visible.value) {
                    Dialog(onDismissRequest = { visible.value = false; events += "dismiss-player" },
                        properties = DialogProperties(usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
                        ListeningPlayerScreen(bookTitle = "山间来信", author = "示例作者", coverPath = null,
                            snapshot = PlaybackSnapshot(bookId = "book-a"), activeBook = true,
                            enabled = true, followAudio = true, paper = Paper, ink = Ink, accent = Forest,
                            voiceState = state.value,
                            onLoadVoices = { events += "load"; state.value = state.value.copy(loading = true) },
                            onSelectVoice = { events += "select:$it" },
                            onClose = { visible.value = false; events += "close-player" },
                            onToggle = { events += "toggle" }, onSeek = { events += "seek" },
                            onSpeed = { events += "speed" }, onFromReading = { events += "from-reading" },
                            onFollowToggle = { events += "follow" }, onLocate = { events += "locate" },
                            onStop = { events += "stop" }, onVoiceSettings = { events += "settings" })
                    }
                }
            }
        }
        return Fixture(state, visible, events)
    }

    private fun voices(repeatName: Boolean): List<TtsVoiceOption> = listOf(
        Locale.SIMPLIFIED_CHINESE, Locale.TAIWAN, Locale.forLanguageTag("zh-HK"),
    ).mapIndexed { index, locale ->
        val name = if (repeatName) "installed-local" else "installed-local-$index"
        TtsVoiceOption(TtsVoiceSelection.voiceId("local.engine", name), name, locale, 400 - index * 100)
    }

    private data class Fixture(
        val state: MutableState<TtsVoiceUiState>,
        val playerVisible: MutableState<Boolean>,
        val events: MutableList<String>,
    )
}
