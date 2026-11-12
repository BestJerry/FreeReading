package com.freereading.audio

/** Audio transitions may publish an unknown duration; UI semantics require a finite fraction. */
internal val PlaybackSnapshot.progressFraction: Float
    get() = when {
        durationMs <= 0L || positionMs <= 0L -> 0f
        positionMs >= durationMs -> 1f
        else -> (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }
