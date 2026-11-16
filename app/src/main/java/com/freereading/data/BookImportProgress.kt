package com.freereading.data

import java.math.BigInteger

enum class ImportStage { WAITING, COPYING, VALIDATING, INDEXING, SAVING, COMPLETED }

/** Progress within the current step; totals come from bytes, resources, or persisted records. */
data class BookImportProgress(
    val stage: ImportStage = ImportStage.WAITING,
    val completed: Long = 0,
    val total: Long? = null,
) {
    val percent: Int?
        get() {
            if (stage == ImportStage.COMPLETED) return 100
            if (stage == ImportStage.WAITING) return null
            val knownTotal = total?.takeIf { it > 0 } ?: return null
            val amount = completed.coerceAtLeast(0)
            if (amount >= knownTotal) return 100
            // Calculate from the original counts: Float rounding can lose a percent or finish early.
            return if (amount <= Long.MAX_VALUE / 100) {
                (amount * 100 / knownTotal).toInt()
            } else {
                BigInteger.valueOf(amount).multiply(BigInteger.valueOf(100))
                    .divide(BigInteger.valueOf(knownTotal)).toInt()
            }
        }

    val fraction: Float?
        get() {
            if (stage == ImportStage.COMPLETED) return 1f
            if (stage == ImportStage.WAITING) return null
            val knownTotal = total?.takeIf { it > 0 } ?: return null
            val amount = completed.coerceAtLeast(0)
            if (amount >= knownTotal) return 1f
            return (amount.toDouble() / knownTotal.toDouble()).toFloat().coerceAtMost(Math.nextDown(1f))
        }
}
