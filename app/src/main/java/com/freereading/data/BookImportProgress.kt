package com.freereading.data

import java.math.BigInteger

/** 导入流水线的阶段，COMPLETED 只在书架记录成功提交后报告。 */
enum class ImportStage { WAITING, COPYING, VALIDATING, INDEXING, SAVING, COMPLETED }

/** 当前导入阶段的进度；总量可按字节、资源数或落盘记录数计算，未知时不显示百分比。 */
data class BookImportProgress(
    val stage: ImportStage = ImportStage.WAITING,
    val completed: Long = 0,
    val total: Long? = null,
) {
    /** 用整数比值计算阶段百分比；极端计数使用 BigInteger 防止乘法溢出。 */
    val percent: Int?
        get() {
            if (stage == ImportStage.COMPLETED) return 100
            if (stage == ImportStage.WAITING) return null
            val knownTotal = total?.takeIf { it > 0 } ?: return null
            val amount = completed.coerceAtLeast(0)
            if (amount >= knownTotal) return 100
            // 直接用原始计数计算，避免 Float 舍入丢失百分比或提前显示完成。
            return if (amount <= Long.MAX_VALUE / 100) {
                (amount * 100 / knownTotal).toInt()
            } else {
                BigInteger.valueOf(amount).multiply(BigInteger.valueOf(100))
                    .divide(BigInteger.valueOf(knownTotal)).toInt()
            }
        }

    /** 提供给进度条的比例；计数尚未到总量时保持小于 1，防止浮点舍入提前满格。 */
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
