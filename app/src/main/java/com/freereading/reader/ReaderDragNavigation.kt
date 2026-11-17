package com.freereading.reader

import kotlin.math.abs
import org.readium.r2.navigator.input.DragEvent

/** 使用系统触摸阈值识别主动浏览，避免轻微触摸就中断听书自动翻页。 */
internal class ReaderDragNavigation(private val touchSlop: Float) {
    init {
        require(touchSlop.isFinite() && touchSlop >= 0f)
    }

    private var active = false
    private var reported = false

    /** 一次拖动只报告一次脱离；分页检测横向移动，滚动模式也检测纵向浏览。 */
    fun shouldDetach(event: DragEvent, scroll: Boolean): Boolean {
        if (event.type == DragEvent.Type.Start) {
            active = true
            reported = false
            return false
        }

        val x = event.offset.x
        val y = event.offset.y
        // 连续滚动模式下 Readium 仍支持横向换章，因此两个方向都要检测。
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
