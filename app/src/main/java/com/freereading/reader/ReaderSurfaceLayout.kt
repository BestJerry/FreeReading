package com.freereading.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** Controls overlay the book instead of taking height from Readium and repaginating it. */
@SuppressLint("ViewConstructor") // Created with its three layers in code, never inflated from XML.
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
