package com.freereading.reader

import android.app.Application
import android.graphics.PointF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.input.DragEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderDragNavigationTest {
    @Test fun readiumsSixPixelStartAtDensityThreeKeepsFollowingUntilTheTouchSlopIsExceeded() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Start, 6f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 12f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 24f, 0f), scroll = false))
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 25f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 48f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 60f, 0f), scroll = false))
    }

    @Test fun cumulativeOffsetsAreComparedWithTheStartInsteadOfAddedTogether() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start, 6f, 0f), scroll = false)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 15f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 20f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 23f, 0f), scroll = false))
    }

    @Test fun paginationDetachesForSteepDiagonalSwipesOnceHorizontalMovementExceedsTheTouchSlop() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start, 0f, 6f), scroll = false)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 0f, 40f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 24f, 80f), scroll = false))
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 25f, 100f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 30f, 120f), scroll = false))
    }

    @Test fun scrollModeDetachesForVerticalBrowsing() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start, 0f, 6f), scroll = true)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 0f, 24f), scroll = true))
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 0f, 25f), scroll = true))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 0f, 40f), scroll = true))
    }

    @Test fun scrollModeAlsoDetachesForHorizontalChapterSwipes() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start, -6f, 0f), scroll = true)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, -24f, 0f), scroll = true))
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, -100f, 0f), scroll = true))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, -120f, 0f), scroll = true))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, -120f, 0f), scroll = true))
    }

    @Test fun negativeOffsetsAndEqualAxesCanDescribeAnIntentionalGesture() {
        val paginated = ReaderDragNavigation(touchSlop = 24f)
        paginated.shouldDetach(event(DragEvent.Type.Start), scroll = false)
        assertTrue(paginated.shouldDetach(event(DragEvent.Type.Move, -25f, 25f), scroll = false))

        val scrolling = ReaderDragNavigation(touchSlop = 24f)
        scrolling.shouldDetach(event(DragEvent.Type.Start), scroll = true)
        assertTrue(scrolling.shouldDetach(event(DragEvent.Type.Move, 25f, -25f), scroll = true))
    }

    @Test fun theFinalEventCanBeTheFirstToExceedTheTouchSlop() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start), scroll = false)
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 20f, 0f), scroll = false))

        assertTrue(gestures.shouldDetach(event(DragEvent.Type.End, 25f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 25f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 30f, 0f), scroll = false))
    }

    @Test fun eachNewStartResetsThePreviousGesturesSingleReport() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start), scroll = false)
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 25f, 0f), scroll = false))

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Start, 50f, 0f), scroll = false))
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 25f, 0f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, 25f, 0f), scroll = false))

        gestures.shouldDetach(event(DragEvent.Type.Start), scroll = false)
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.End, 25f, 0f), scroll = false))
    }

    @Test fun nonFiniteOffsetsCannotDetachOrConsumeTheGesturesReport() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (scroll in listOf(false, true)) {
                val gestures = ReaderDragNavigation(touchSlop = 24f)
                gestures.shouldDetach(event(DragEvent.Type.Start), scroll)

                assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, invalid, 30f), scroll))
                assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 30f, invalid), scroll))
                assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 30f, 30f), scroll))
            }
        }
    }

    @Test fun anInvalidFinalEventStillEndsTheGesture() {
        val gestures = ReaderDragNavigation(touchSlop = 24f)
        gestures.shouldDetach(event(DragEvent.Type.Start), scroll = false)

        assertFalse(gestures.shouldDetach(event(DragEvent.Type.End, Float.NaN, 30f), scroll = false))
        assertFalse(gestures.shouldDetach(event(DragEvent.Type.Move, 30f, 0f), scroll = false))
        gestures.shouldDetach(event(DragEvent.Type.Start), scroll = false)
        assertTrue(gestures.shouldDetach(event(DragEvent.Type.Move, 30f, 0f), scroll = false))
    }

    private fun event(type: DragEvent.Type, x: Float = 0f, y: Float = 0f) =
        DragEvent(type = type, start = PointF(100f, 100f), offset = PointF(x, y))
}
