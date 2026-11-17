package com.freereading.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** 将正文、加载遮罩和阅读控件叠放，菜单显隐不改变 Readium 高度或触发重新分页。 */
@SuppressLint("ViewConstructor") // 三层视图由代码传入，不通过 XML 创建。
internal class ReaderSurfaceLayout(
    context: Context,
    document: View,
    opening: View,
    chrome: View,
) : FrameLayout(context) {
    init {
        for (layer in listOf(document, opening, chrome)) {
            addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
