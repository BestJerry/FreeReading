package com.freereading.reader

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "mdpi")
class ReaderSurfaceLayoutTest {
    @Test fun openingAndControlsNeverShrinkTheReadingPage() {
        val context = RuntimeEnvironment.getApplication()
        val article = View(context)
        val document = FrameLayout(context).apply {
            setPadding(0, 0, 0, 28)
            addView(article, FrameLayout.LayoutParams(-1, -1))
        }
        val opening = View(context)
        val chrome = View(context).apply { minimumHeight = 280 }
        val host = ReaderSurfaceLayout(context, document, opening, chrome)
        host.setPadding(0, 24, 0, 16)

        fun layout(width: Int, height: Int) {
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
        }

        layout(360, 720)
        assertEquals(680, document.height)
        assertEquals(652, article.height)
        assertEquals(24, document.top)
        opening.visibility = View.GONE
        chrome.visibility = View.GONE
        layout(360, 720)
        assertEquals(652, article.height)
        chrome.visibility = View.VISIBLE
        layout(360, 720)
        assertEquals(652, article.height)

        layout(720, 360)
        assertEquals(292, article.height)
        assertEquals(720, document.width)
    }
}
