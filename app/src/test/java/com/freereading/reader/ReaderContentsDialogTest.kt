package com.freereading.reader

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import com.freereading.ui.Ink
import com.freereading.ui.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderContentsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingALargeTableStartsAtTheCurrentChapterAndMarksOnlyOneEntry() {
        showContents(currentIndex = 1_700)

        compose.onNodeWithTag("reader-contents-item-1700").assertIsDisplayed().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前章节"))
        compose.onNodeWithText("当前章节").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-item-0").assertDoesNotExist()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        assertListHeightAtMost380Dp()
    }

    @Test fun closingAndReopeningUsesTheNewReadingChapter() {
        val fixture = showContents(currentIndex = 300)
        compose.onNodeWithTag("reader-contents-item-300").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("reader-contents-close").performClick()
        compose.onNodeWithTag("reader-contents-dialog").assertDoesNotExist()

        compose.runOnIdle {
            fixture.currentIndex.value = 1_650
            fixture.visible.value = true
        }

        compose.onNodeWithTag("reader-contents-item-1650").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("reader-contents-item-300").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.dismissals.value) }
    }

    @Test fun nestedEntriesWithRepeatedHrefsReturnTheOriginalLink() {
        val entries = List(30) { index ->
            ContentsEntry(Link(href = Url("text/shared.xhtml#repeated")!!,
                title = if (index == 11) " " else "目录项 $index"), depth = index % 4,
                fallbackTitle = "第 ${index + 1} 节")
        }
        val fixture = showContents(entries = entries, currentIndex = 11)

        compose.onNodeWithText("第 12 节").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-item-11").assertIsSelected().performClick()

        compose.runOnIdle {
            assertEquals(1, fixture.selections.size)
            assertSame(entries[11], fixture.selections.single())
            assertSame(entries[11].link, fixture.selections.single().link)
            assertEquals(3, fixture.selections.single().depth)
        }
    }

    @Test fun anUnknownChapterStartsAtTheFirstEntryWithoutInventingASelection() {
        showContents(currentIndex = null)

        compose.onNodeWithTag("reader-contents-item-0").assertIsDisplayed()
        compose.onNodeWithText("当前章节").assertDoesNotExist()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
    }

    @Test fun theFirstAsynchronouslyResolvedChapterCanCorrectTheOpeningPositionOnce() {
        val fixture = showContents(currentIndex = null)
        compose.onNodeWithTag("reader-contents-item-0").assertIsDisplayed()
        compose.runOnIdle { fixture.currentIndex.value = 1_400 }

        compose.onNodeWithTag("reader-contents-item-1400").assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { fixture.currentIndex.value = 1_700 }

        compose.onNodeWithTag("reader-contents-item-1400").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("reader-contents-item-1700").assertDoesNotExist()
    }

    @Test fun resolvingTheChapterAfterManualBrowsingDoesNotPullTheListBack() {
        val fixture = showContents(currentIndex = null)
        compose.onNodeWithTag("reader-contents-list").performScrollToIndex(500)
        compose.onNodeWithTag("reader-contents-item-500").assertIsDisplayed()
        compose.runOnIdle { fixture.currentIndex.value = 1_500 }

        compose.onNodeWithTag("reader-contents-item-500").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-item-1500").assertDoesNotExist()
        compose.onNodeWithTag("reader-contents-list").performScrollToIndex(1_500)
        compose.onNodeWithTag("reader-contents-item-1500").assertIsDisplayed().assertIsSelected()
    }

    @Test fun playbackUpdatesDoNotUndoBrowsingFromAnAlreadyKnownChapter() {
        val fixture = showContents(currentIndex = 1_200)
        compose.onNodeWithTag("reader-contents-list").performScrollToIndex(20)
        compose.onNodeWithTag("reader-contents-item-20").assertIsDisplayed()
        compose.runOnIdle { fixture.currentIndex.value = 1_800 }

        compose.onNodeWithTag("reader-contents-item-20").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-item-1800").assertDoesNotExist()
    }

    @Test fun anIndexPastTheEndIsClampedToTheLastEntry() {
        showContents(entries = entries(30), currentIndex = 5_000)

        compose.onNodeWithTag("reader-contents-item-29").assertIsDisplayed().assertIsSelected()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowNightContentsWithLargeTextKeepsTheCurrentMarkerAndCloseUsable() {
        val entries = entries().map { entry -> entry.copy(depth = 3,
            link = entry.link.copy(title = "${entry.link.title} 山间来信，风穿过树林")) }
        val fixture = showContents(entries = entries, currentIndex = 1_800, fontScale = 1.8f,
            paper = Color(0xFF1E2422), ink = Color(0xFFE4E9E3), accent = Color(0xFFB5CDB5))

        compose.onNodeWithTag("reader-contents-item-1800").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("当前章节").assertIsDisplayed()
        assertListHeightAtMost380Dp()
        compose.onNodeWithTag("reader-contents-list").performScrollToIndex(1_850)
        compose.onNodeWithTag("reader-contents-item-1850").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader-contents-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.dismissals.value) }
    }

    private fun assertListHeightAtMost380Dp() {
        val bounds = compose.onNodeWithTag("reader-contents-list").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("Contents list height must fit within 380dp: $height", height <= 380.5.dp)
    }

    private fun showContents(
        entries: List<ContentsEntry> = entries(),
        currentIndex: Int?,
        fontScale: Float = 1f,
        paper: Color = Paper,
        ink: Color = Ink,
        accent: Color = Forest,
    ): Fixture {
        val current = mutableStateOf(currentIndex)
        val visible = mutableStateOf(true)
        val dismissals = mutableStateOf(0)
        val selections = mutableListOf<ContentsEntry>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FreeReadingTheme {
                    if (visible.value) ReaderContentsDialog(entries = entries, currentIndex = current.value,
                        paper = paper, ink = ink, accent = accent,
                        onDismiss = { dismissals.value += 1; visible.value = false },
                        onSelect = { selections += it })
                }
            }
        }
        return Fixture(current, visible, dismissals, selections)
    }

    private fun entries(count: Int = 2_000) = List(count) { index ->
        ContentsEntry(Link(href = Url("text/chapter-$index.xhtml")!!, title = "第 ${index + 1} 章"),
            depth = 0, fallbackTitle = "第 ${index + 1} 节")
    }

    private data class Fixture(
        val currentIndex: MutableState<Int?>,
        val visible: MutableState<Boolean>,
        val dismissals: MutableState<Int>,
        val selections: MutableList<ContentsEntry>,
    )
}
