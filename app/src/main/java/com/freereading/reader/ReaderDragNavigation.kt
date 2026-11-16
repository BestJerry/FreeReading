package com.freereading.reader

import kotlin.math.abs
import org.readium.r2.navigator.input.DragEvent

/** Distinguishes an intentional reading gesture from Readium's small drag-start threshold. */
internal class ReaderDragNavigation(private val touchSlop: Float) {
    init {
        require(touchSlop.isFinite() && touchSlop >= 0f)
    }

    private var active = false
    private var reported = false

    fun shouldDetach(event: DragEvent, scroll: Boolean): Boolean {
        if (event.type == DragEvent.Type.Start) {
            active = true
            reported = false
            return false
        }

        val x = event.offset.x
        val y = event.offset.y
        // Readium also accepts horizontal chapter swipes while vertical scrolling is enabled.
        val distance = if (scroll) maxOf(abs(x), abs(y)) else abs(x)
        val detach = active && !reported && x.isFinite() && y.isFinite() &&
            distance > touchSlop
        if (detach) reported = true
        if (event.type == DragEvent.Type.End) {
            active = false
            reported = false
        }
        return detach
    }
}
