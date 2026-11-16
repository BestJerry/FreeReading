package com.freereading.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportProgressReporterTest {
    @Test fun ordinaryUpdatesAreThrottledAgainstAnInjectedMonotonicClock() {
        var now = 0L
        val events = ArrayList<BookImportProgress>()
        val reporter = ImportProgressReporter({ events.add(it) }, { now })
        reporter.report(ImportStage.COPYING, 0)
        repeat(99) { reporter.report(ImportStage.COPYING, it + 1L) }
        now = 99
        reporter.report(ImportStage.COPYING, 100)
        assertEquals(listOf(0L), events.map { it.completed })

        now = 100
        reporter.report(ImportStage.COPYING, 101)
        assertEquals(listOf(0L, 101L), events.map { it.completed })
    }

    @Test fun stageChangesAndCompletionAreDeliveredEvenWithoutAdvancingTime() {
        val events = ArrayList<BookImportProgress>()
        val reporter = ImportProgressReporter({ events.add(it) }, { 0 })
        reporter.report(ImportStage.WAITING)
        reporter.report(ImportStage.INDEXING, 0, 4)
        reporter.report(ImportStage.INDEXING, 2, 4)
        reporter.report(ImportStage.INDEXING, 4, 4)
        reporter.report(ImportStage.SAVING)
        reporter.report(ImportStage.COMPLETED)

        assertEquals(listOf(ImportStage.WAITING, ImportStage.INDEXING, ImportStage.INDEXING,
            ImportStage.SAVING, ImportStage.COMPLETED), events.map { it.stage })
        assertEquals(4L, events[2].completed)
    }

    @Test fun newlyKnownOrWithdrawnTotalsAreNotHiddenByThrottling() {
        val events = ArrayList<BookImportProgress>()
        val reporter = ImportProgressReporter({ events.add(it) }, { 0 })
        reporter.report(ImportStage.COPYING, 0)
        reporter.report(ImportStage.COPYING, 10, 100)
        reporter.report(ImportStage.COPYING, 110)
        reporter.report(ImportStage.COPYING, 110, 110)

        assertEquals(listOf(null, 100L, null, 110L), events.map { it.total })
        assertEquals(listOf(0L, 10L, 110L, 110L), events.map { it.completed })
    }

    @Test fun duplicateSnapshotsAreNotEmittedAgainAfterTheThrottleInterval() {
        var now = 0L
        val events = ArrayList<BookImportProgress>()
        val reporter = ImportProgressReporter({ events.add(it) }, { now })
        reporter.report(ImportStage.VALIDATING, 4, 4)
        now = 1_000
        reporter.report(ImportStage.VALIDATING, 4, 4)
        assertEquals(1, events.size)
    }
}
