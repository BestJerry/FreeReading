package com.freereading.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportProgressTest {
    private val workingStages = listOf(ImportStage.COPYING, ImportStage.VALIDATING,
        ImportStage.INDEXING, ImportStage.SAVING)

    @Test fun waitingHasNoPercentageEvenIfAnOldTotalIsPresent() {
        assertNull(BookImportProgress().fraction)
        assertNull(BookImportProgress().percent)
        assertNull(BookImportProgress(ImportStage.WAITING, completed = 50, total = 100).fraction)
        assertNull(BookImportProgress(ImportStage.WAITING, completed = 50, total = 100).percent)
    }

    @Test fun absentZeroAndNegativeTotalsAreIndeterminateInEveryWorkingStage() {
        for (stage in workingStages) {
            for (total in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
                assertNull(BookImportProgress(stage, completed = Long.MAX_VALUE, total = total).fraction)
                assertNull(BookImportProgress(stage, completed = Long.MAX_VALUE, total = total).percent)
            }
        }
    }

    @Test fun knownProgressUsesTheCurrentStagesCounts() {
        for (stage in workingStages) {
            assertEquals(0.25f, BookImportProgress(stage, 25, 100).fraction!!, 0f)
            assertEquals(1f / 3f, BookImportProgress(stage, 1, 3).fraction!!, 0.000001f)
        }
    }

    @Test fun everyWholePercentageUsesExactCountsIncludingFiftyThreeAndFiftyNine() {
        for (stage in workingStages) {
            for (completed in 0L..100L) {
                assertEquals(completed.toInt(), BookImportProgress(stage, completed, 100).percent)
            }
            assertEquals(33, BookImportProgress(stage, 1, 3).percent)
            assertEquals(37, BookImportProgress(stage, 3, 8).percent)
        }
    }

    @Test fun anIncompleteLargeCopyCannotRoundUpToFinished() {
        for (stage in workingStages) {
            val progress = BookImportProgress(stage, 67_108_864, 67_108_865)
            assertEquals(99, progress.percent)
            assertTrue(progress.fraction!! < 1f)
            assertEquals(Math.nextDown(1f), progress.fraction!!, 0f)
        }
    }

    @Test fun extremePercentagesRemainExactWithoutMultiplicationOverflow() {
        for (stage in workingStages) {
            assertEquals(0, BookImportProgress(stage, 1, Long.MAX_VALUE).percent)
            assertEquals(49, BookImportProgress(stage, Long.MAX_VALUE / 2, Long.MAX_VALUE).percent)
            val almostFinished = BookImportProgress(stage, Long.MAX_VALUE - 1, Long.MAX_VALUE)
            assertEquals(99, almostFinished.percent)
            assertTrue(almostFinished.fraction!! < 1f)
            assertEquals(100, BookImportProgress(stage, Long.MAX_VALUE, Long.MAX_VALUE).percent)
        }
    }

    @Test fun negativeCountsAndOverrunsAreClamped() {
        for (stage in workingStages) {
            assertEquals(0f, BookImportProgress(stage, Long.MIN_VALUE, 1).fraction!!, 0f)
            assertEquals(0f, BookImportProgress(stage, -1, Long.MAX_VALUE).fraction!!, 0f)
            assertEquals(1f, BookImportProgress(stage, Long.MAX_VALUE, 1).fraction!!, 0f)
            assertEquals(1f, BookImportProgress(stage, 101, 100).fraction!!, 0f)
            assertEquals(0, BookImportProgress(stage, Long.MIN_VALUE, 1).percent)
            assertEquals(0, BookImportProgress(stage, -1, Long.MAX_VALUE).percent)
            assertEquals(100, BookImportProgress(stage, Long.MAX_VALUE, 1).percent)
            assertEquals(100, BookImportProgress(stage, 101, 100).percent)
        }
    }

    @Test fun longExtremesCannotOverflowOrProduceNaNOrInfinity() {
        for (stage in workingStages) {
            assertEquals(1f, BookImportProgress(stage, Long.MAX_VALUE, Long.MAX_VALUE).fraction!!, 0f)
            assertEquals(0.5f, BookImportProgress(stage, Long.MAX_VALUE / 2, Long.MAX_VALUE).fraction!!, 0.000001f)
            for (completed in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE / 2, Long.MAX_VALUE)) {
                for (total in listOf(1L, Long.MAX_VALUE)) {
                    val value = BookImportProgress(stage, completed, total).fraction!!
                    assertTrue(value.isFinite() && value in 0f..1f)
                }
            }
        }
    }

    @Test fun completedIsAlwaysExactlyOneDespiteUnknownOrStaleCounters() {
        for (total in listOf(null, 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(1f, BookImportProgress(ImportStage.COMPLETED, Long.MIN_VALUE, total).fraction!!, 0f)
            assertEquals(100, BookImportProgress(ImportStage.COMPLETED, Long.MIN_VALUE, total).percent)
        }
    }
}
