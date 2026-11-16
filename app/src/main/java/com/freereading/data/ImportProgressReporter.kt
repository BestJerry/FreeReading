package com.freereading.data

import android.os.SystemClock

/** Called on the import worker; only emitted events allocate a public progress snapshot. */
internal class ImportProgressReporter(
    private val onProgress: (BookImportProgress) -> Unit,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastStage: ImportStage? = null
    private var lastTotal: Long? = null
    private var lastCompleted = -1L
    private var lastEmission = 0L

    fun report(stage: ImportStage, completed: Long = 0, total: Long? = null) {
        val amount = completed.coerceAtLeast(0)
        val knownTotal = total?.takeIf { it > 0 }
        val stageChanged = stage != lastStage
        val totalChanged = knownTotal != lastTotal
        val finished = stage == ImportStage.COMPLETED || (knownTotal != null && amount >= knownTotal)
        if (!stageChanged && !totalChanged && amount == lastCompleted) return
        val now = elapsedMillis()
        if (!stageChanged && !totalChanged && !finished && now - lastEmission < 100) return
        onProgress(BookImportProgress(stage, amount, knownTotal))
        lastStage = stage
        lastTotal = knownTotal
        lastCompleted = amount
        lastEmission = now
    }
}
