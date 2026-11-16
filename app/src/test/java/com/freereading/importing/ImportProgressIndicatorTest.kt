package com.freereading.importing

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.freereading.R
import com.freereading.data.BookImportProgress
import com.freereading.data.ImportStage
import com.freereading.ui.FreeReadingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportProgressIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitingShowsAnIndeterminateBarWithoutAnInventedPercentage() {
        show(BookImportProgress())

        compose.onNodeWithTag("import_progress_stage").assertTextEquals("准备导入")
        compose.onNodeWithTag("import_progress_percent").assertDoesNotExist()
        compose.onNodeWithTag("import_progress_detail").assertDoesNotExist()
        assertProgress(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithContentDescription("书籍导入进度").assertIsDisplayed()
    }

    @Test fun copyingShowsItsStepPercentageAndActualMegabytes() {
        show(BookImportProgress(ImportStage.COPYING, 1_048_576, 4_194_304))

        compose.onNodeWithTag("import_progress_stage").assertTextEquals("复制文件")
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 25%")
        compose.onNodeWithTag("import_progress_detail").assertTextContains("第 1/4 步", substring = true)
            .assertTextContains(app().getString(R.string.import_progress_copy_known, 1.0, 4.0), substring = true)
        assertProgress(ProgressBarRangeInfo(0.25f, 0f..1f))
    }

    @Test fun exactPercentagesAndAnIncompleteCopyStayCorrectAcrossRecomposition() {
        var progress by mutableStateOf(BookImportProgress(ImportStage.VALIDATING, 53, 100))
        compose.setContent { FreeReadingTheme { ImportProgressIndicator(progress) } }

        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 53%")
        assertProgress(ProgressBarRangeInfo(0.53f, 0f..1f))
        compose.runOnIdle { progress = BookImportProgress(ImportStage.VALIDATING, 59, 100) }
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 59%")
        assertProgress(ProgressBarRangeInfo(0.59f, 0f..1f))
        compose.runOnIdle { progress = BookImportProgress(ImportStage.COPYING, 67_108_864, 67_108_865) }
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 99%")
        compose.onNodeWithText("导入完成").assertDoesNotExist()
        assertProgress(ProgressBarRangeInfo(Math.nextDown(1f), 0f..1f))
    }

    @Test fun unknownCopyLengthShowsCopiedBytesAndAnIndeterminateBar() {
        show(BookImportProgress(ImportStage.COPYING, 2_097_152))

        compose.onNodeWithTag("import_progress_percent").assertDoesNotExist()
        compose.onNodeWithTag("import_progress_detail")
            .assertTextContains(app().getString(R.string.import_progress_copy_unknown, 2.0), substring = true)
        assertProgress(ProgressBarRangeInfo.Indeterminate)
    }

    @Test fun validationAndIndexingIdentifyTheirOwnStepAndIndexingResourceCount() {
        var progress by mutableStateOf(BookImportProgress(ImportStage.VALIDATING, 7, 10))
        compose.setContent { FreeReadingTheme { ImportProgressIndicator(progress) } }

        compose.onNodeWithTag("import_progress_stage").assertTextEquals("检查书籍")
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 70%")
        compose.onNodeWithTag("import_progress_detail").assertTextEquals("第 2/4 步")
        compose.runOnIdle { progress = BookImportProgress(ImportStage.INDEXING, 3, 8) }
        compose.onNodeWithTag("import_progress_stage").assertTextEquals("整理正文")
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 37%")
        compose.onNodeWithTag("import_progress_detail").assertTextContains("第 3/4 步", substring = true)
            .assertTextContains("已整理 3 / 8 节", substring = true)
        assertProgress(ProgressBarRangeInfo(0.375f, 0f..1f))
    }

    @Test fun savingShowsOnlyItsStepAndPercentageWithoutInternalRecordCounts() {
        show(BookImportProgress(ImportStage.SAVING, 218, 1_000))

        compose.onNodeWithTag("import_progress_stage").assertTextEquals("保存书籍")
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 21%")
        compose.onNodeWithTag("import_progress_detail").assertTextEquals("第 4/4 步")
        compose.onNodeWithText("218", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1000", substring = true).assertDoesNotExist()
        assertProgress(ProgressBarRangeInfo(0.218f, 0f..1f))
    }

    @Test fun completedRemainsAtOneHundredPercentWithoutAnUnknownTotal() {
        show(BookImportProgress(ImportStage.COMPLETED))

        compose.onNodeWithTag("import_progress_stage").assertTextEquals("导入完成")
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("100%")
        assertProgress(ProgressBarRangeInfo(1f, 0f..1f))
    }

    @Test fun negativeHugeAndUnknownCountersRemainSafeAcrossRecomposition() {
        var progress by mutableStateOf(BookImportProgress(ImportStage.COPYING, -10, Long.MAX_VALUE))
        compose.setContent { FreeReadingTheme { ImportProgressIndicator(progress) } }

        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 0%")
        assertProgress(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle { progress = BookImportProgress(ImportStage.INDEXING, Long.MAX_VALUE, Long.MAX_VALUE) }
        assertProgress(ProgressBarRangeInfo(1f, 0f..1f))
        compose.runOnIdle { progress = BookImportProgress(ImportStage.SAVING, 1, 0) }
        compose.onNodeWithTag("import_progress_percent").assertDoesNotExist()
        assertProgress(ProgressBarRangeInfo.Indeterminate)
    }

    @Test fun finishingOneStepDoesNotClaimThatTheWholeImportIsComplete() {
        var progress by mutableStateOf(BookImportProgress(ImportStage.COPYING, 100, 100))
        compose.setContent { FreeReadingTheme { ImportProgressIndicator(progress) } }

        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 100%")
        compose.onNodeWithText("导入完成").assertDoesNotExist()
        compose.runOnIdle { progress = BookImportProgress(ImportStage.INDEXING, 0, 10) }
        compose.onNodeWithTag("import_progress_percent").assertTextEquals("本阶段 0%")
        compose.onNodeWithTag("import_progress_detail").assertTextContains("第 3/4 步", substring = true)
        assertProgress(ProgressBarRangeInfo(0f, 0f..1f))
    }

    private fun show(progress: BookImportProgress) {
        compose.setContent { FreeReadingTheme { ImportProgressIndicator(progress) } }
    }

    private fun assertProgress(expected: ProgressBarRangeInfo) {
        compose.onNodeWithTag("import_progress_bar").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, expected),
        )
    }

    private fun app(): Application = RuntimeEnvironment.getApplication()
}
