package com.freereading.audio

/** positionMs is media time: speed changes do not alter the text/audio correspondence. */
internal data class SentencePosition(val index: Int, val positionMs: Long)

internal data class PendingRelativeSeek(
    val anchor: SentencePosition,
    val deltaMs: Long,
    val revision: Long,
)

/** Keeps relative button presses additive while the original seek is awaiting synthesis. */
internal class RelativeSeekAccumulator {
    private var pending: PendingRelativeSeek? = null
    private var revision = 0L

    fun enqueue(anchor: SentencePosition, deltaMs: Long): PendingRelativeSeek {
        val previous = pending
        val total = ((previous?.deltaMs ?: 0) + deltaMs.coerceIn(-LIMIT_MS, LIMIT_MS))
            .coerceIn(-LIMIT_MS, LIMIT_MS)
        return PendingRelativeSeek(previous?.anchor ?: anchor, total, ++revision).also { pending = it }
    }

    fun complete(request: PendingRelativeSeek) {
        if (pending?.revision == request.revision) pending = null
    }

    fun clear() { pending = null }
    private companion object { const val LIMIT_MS = 3_600_000L }
}

internal object SentenceTimeline {
    /** Loads real durations as needed; unknown sentences must be synthesized, never estimated. */
    suspend fun seek(
        sentenceCount: Int,
        startIndex: Int,
        startPositionMs: Long,
        deltaMs: Long,
        duration: suspend (Int) -> Long,
    ): SentencePosition {
        require(sentenceCount > 0)
        var index = startIndex.coerceIn(0, sentenceCount - 1)
        var length = duration(index).also { require(it > 0) }
        var position = startPositionMs.coerceIn(0, length)
        if (deltaMs >= 0) {
            var remaining = deltaMs
            while (true) {
                val available = length - position
                if (remaining < available || index == sentenceCount - 1) {
                    return SentencePosition(index, position + remaining.coerceAtMost(available))
                }
                remaining -= available
                index += 1
                position = 0
                length = duration(index).also { require(it > 0) }
            }
        }
        var remaining = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else -deltaMs
        while (true) {
            if (remaining <= position || index == 0) {
                return SentencePosition(index, position - remaining.coerceAtMost(position))
            }
            remaining -= position
            index -= 1
            length = duration(index).also { require(it > 0) }
            position = length
        }
    }
}
