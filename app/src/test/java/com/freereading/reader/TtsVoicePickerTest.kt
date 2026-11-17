package com.freereading.reader

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.freereading.audio.TtsVoiceCatalog
import com.freereading.audio.TtsVoiceOption
import com.freereading.audio.TtsVoiceUiState
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import com.freereading.ui.Ink
import com.freereading.ui.Paper
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TtsVoicePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticChoiceShowsTheActualEngineVoiceAndLocale() {
        showPicker()

        compose.onNodeWithTag("tts-voice-automatic").assertIsSelected()
        compose.onNodeWithTag("tts-voice-voice-a").assertIsNotSelected()
        compose.onNodeWithText("当前使用：zh-local-A").assertIsDisplayed()
        compose.onNodeWithText("zh-local-A").assertIsDisplayed()
        compose.onNodeWithText(Locale.SIMPLIFIED_CHINESE.getDisplayName(Locale.getDefault())).assertIsDisplayed()
        compose.onNodeWithText("女声").assertDoesNotExist()
        compose.onNodeWithText("男声").assertDoesNotExist()
    }

    @Test fun selectionWaitsForTheConfirmedCatalogAndKeepsTheDialogOpen() {
        val state = mutableStateOf(sampleState())
        val events = showPicker(state)

        compose.onNodeWithTag("tts-voice-voice-b").performClick()
        compose.onNodeWithTag("tts-voice-automatic").assertIsSelected()
        compose.onNodeWithTag("tts-voice-voice-b").assertIsNotSelected()
        compose.onNodeWithTag("tts-voice-dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("select:voice-b"), events)
            state.value = state.value.copy(catalog = catalog(selected = "voice-b"))
        }
        compose.onNodeWithTag("tts-voice-voice-b").assertIsSelected()
        compose.onNodeWithTag("tts-voice-automatic").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf("select:voice-b", "select:null"), events) }
    }

    @Test fun switchingRetainsThePreviousRadioAndBlocksDuplicateCommands() {
        val events = showPicker(mutableStateOf(sampleState().copy(switching = true,
            catalog = catalog(selected = "voice-a"))))

        compose.onNodeWithText("正在切换音色…").assertIsDisplayed()
        compose.onNodeWithTag("tts-voice-voice-a").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-voice-b").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-automatic").assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-retry").assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-done").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("dismiss"), events) }
    }

    @Test fun loadingShowsProgressAndLeavesDismissalAvailable() {
        val events = showPicker(mutableStateOf(TtsVoiceUiState(loading = true)))

        compose.onNodeWithText("正在加载离线音色…").assertIsDisplayed()
        compose.onNodeWithTag("tts-voice-empty").assertDoesNotExist()
        compose.onNodeWithTag("tts-voice-automatic").assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-retry").assertIsNotEnabled()
        compose.onNodeWithTag("tts-voice-done").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("dismiss"), events) }
    }

    @Test fun emptyCatalogOffersSystemSettingsAndRetry() {
        val events = showPicker(mutableStateOf(sampleState().copy(
            catalog = catalog().copy(voices = emptyList(), effectiveVoiceId = null))))

        compose.onNodeWithTag("tts-voice-empty").assertIsDisplayed()
        compose.onNodeWithTag("tts-voice-settings").assertIsEnabled().performClick()
        compose.onNodeWithTag("tts-voice-retry").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("settings", "retry"), events) }
    }

    @Test fun errorOffersRetryAndSettingsWithoutClaimingASelectionSucceeded() {
        val events = showPicker(mutableStateOf(sampleState().copy(error = "无法切换音色，请重试")))

        compose.onNodeWithText("无法切换音色，请重试").assertIsDisplayed()
        compose.onNodeWithTag("tts-voice-automatic").assertIsSelected()
        compose.onNodeWithTag("tts-voice-retry").assertIsEnabled().performClick()
        compose.onNodeWithTag("tts-voice-list").performScrollToNode(hasTestTag("tts-voice-settings"))
        compose.onNodeWithTag("tts-voice-settings").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("retry", "settings"), events) }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowNightPickerWithLargeTextCanScrollToAndSelectTheLastVoice() {
        val voices = (0..24).map { index ->
            TtsVoiceOption("voice-$index", "zh-CN-installed-offline-voice-$index-long-name",
                Locale.SIMPLIFIED_CHINESE, 300)
        }
        val events = showPicker(mutableStateOf(sampleState().copy(catalog = TtsVoiceCatalog(
            "system.engine", voices, null, voices.first().id))), fontScale = 1.8f,
            paper = Color(0xFF1C211E), ink = Color(0xFFE3DDCC))

        compose.onNodeWithTag("tts-voice-done").assertIsDisplayed()
        compose.onNodeWithTag("tts-voice-list").performScrollToNode(hasTestTag("tts-voice-voice-24"))
        compose.onNodeWithTag("tts-voice-voice-24").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tts-voice-list").performScrollToNode(hasTestTag("tts-voice-settings"))
        compose.onNodeWithTag("tts-voice-settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tts-voice-done").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(listOf("select:voice-24", "settings", "dismiss"), events) }
    }

    private fun showPicker(
        state: MutableState<TtsVoiceUiState> = mutableStateOf(sampleState()),
        fontScale: Float = 1f,
        paper: Color = Paper,
        ink: Color = Ink,
    ): MutableList<String> {
        val events = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FreeReadingTheme {
                    TtsVoicePicker(state.value, paper = paper, ink = ink, accent = Forest,
                        onDismiss = { events += "dismiss" }, onRetry = { events += "retry" },
                        onSelect = { events += "select:$it" }, onVoiceSettings = { events += "settings" })
                }
            }
        }
        return events
    }

    private fun sampleState() = TtsVoiceUiState(bookId = "book-a", language = "zh-CN", catalog = catalog())

    private fun catalog(selected: String? = null) = TtsVoiceCatalog("system.engine", listOf(
        TtsVoiceOption("voice-a", "zh-local-A", Locale.SIMPLIFIED_CHINESE, 400),
        TtsVoiceOption("voice-b", "zh-local-B", Locale.TAIWAN, 300),
    ), selected, selected ?: "voice-a")
}
