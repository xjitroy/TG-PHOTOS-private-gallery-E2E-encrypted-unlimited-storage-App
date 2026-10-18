package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ViewerFilmstripGeometryTest {
    @Test fun firstAndLastCanOccupyTheSameCenteredSlot() {
        // 320px viewport, 44px thumbnail, 138px edge padding.
        for (index in listOf(0, 499)) {
            assertEquals(index, ViewerFilmstripGeometry.nearest(listOf(FilmstripItem(index, 0, 44)), -138, 182, 500))
            assertEquals(FilmstripScrollTarget(index, 0), ViewerFilmstripGeometry.target(index.toFloat(), 500, 48f))
        }
    }

    @Test fun usesPaddedViewportRatherThanRawScreenCenter() {
        val items = listOf(FilmstripItem(8, -48, 44), FilmstripItem(9, 0, 44), FilmstripItem(10, 48, 44), FilmstripItem(11, 96, 44))
        assertEquals(9, ViewerFilmstripGeometry.nearest(items, -138, 182, 500))
    }

    @Test fun scrubbingSelectsTheThumbnailCrossingCenter() {
        val items = listOf(FilmstripItem(8, -30, 44), FilmstripItem(9, 18, 44), FilmstripItem(10, 66, 44))
        assertEquals(9, ViewerFilmstripGeometry.nearest(items, -138, 182, 500))
        assertEquals(8, ViewerFilmstripGeometry.nearest(items.map { it.copy(offset = it.offset + 20) }, -138, 182, 500))
    }

    @Test fun mainSwipeTracksFractionalPositionInEitherDirection() {
        assertEquals(FilmstripScrollTarget(10, 12), ViewerFilmstripGeometry.target(10.25f, 500, 48f))
        assertEquals(FilmstripScrollTarget(9, 36), ViewerFilmstripGeometry.target(9.75f, 500, 48f))
    }

    @Test fun overscrollCannotRequestAnInvalidPage() {
        assertEquals(FilmstripScrollTarget(0, 0), ViewerFilmstripGeometry.target(-.5f, 500, 48f))
        assertEquals(FilmstripScrollTarget(499, 0), ViewerFilmstripGeometry.target(501.5f, 500, 48f))
        assertEquals(FilmstripScrollTarget(0, 0), ViewerFilmstripGeometry.target(10f, 1, 48f))
    }

    @Test fun invalidAndUnmeasuredLayoutsCannotDriveNavigation() {
        assertNull(ViewerFilmstripGeometry.nearest(emptyList(), 0, 320, 500))
        assertNull(ViewerFilmstripGeometry.nearest(listOf(FilmstripItem(1, 0, 44)), 0, 0, 500))
        assertNull(ViewerFilmstripGeometry.nearest(listOf(FilmstripItem(-1, 0, 44), FilmstripItem(500, 0, 44), FilmstripItem(0, 0, 0)), 0, 320, 500))
        assertNull(ViewerFilmstripGeometry.target(Float.NaN, 500, 48f))
        assertNull(ViewerFilmstripGeometry.target(Float.POSITIVE_INFINITY, 500, 48f))
        assertNull(ViewerFilmstripGeometry.target(10f, 0, 48f))
        assertNull(ViewerFilmstripGeometry.target(10f, 500, 0f))
    }

    @Test fun densityAndWindowResizePreserveTheSelectedIndex() {
        assertEquals(20, ViewerFilmstripGeometry.nearest(listOf(FilmstripItem(20, 0, 132)), -414, 546, 500))
        assertEquals(20, ViewerFilmstripGeometry.nearest(listOf(FilmstripItem(20, 0, 44)), -278, 322, 500))
        assertEquals(FilmstripScrollTarget(20, 36), ViewerFilmstripGeometry.target(20.25f, 500, 144f))
    }

    @Test fun mirroredVisibleOffsetsChooseTheSameCenter() {
        val items = listOf(FilmstripItem(1, 82, 44), FilmstripItem(2, 136, 44), FilmstripItem(3, 190, 44))
        assertEquals(2, ViewerFilmstripGeometry.nearest(items, 0, 320, 5))
        assertEquals(2, ViewerFilmstripGeometry.nearest(items.map { it.copy(offset = 320 - it.offset - it.size) }, 0, 320, 5))
    }

    @Test fun dragIsClampedAndMirrorsForRtlWithoutChangingTheRealPager() {
        assertEquals(11f, ViewerFilmstripGeometry.drag(10f, -48f, 48f, 500, false))
        assertEquals(11f, ViewerFilmstripGeometry.drag(10f, 48f, 48f, 500, true))
        assertEquals(0f, ViewerFilmstripGeometry.drag(0f, 10000f, 48f, 500, false))
        assertEquals(499f, ViewerFilmstripGeometry.drag(499f, -10000f, 48f, 500, false))
    }

    @Test fun flingCannotScheduleAnUnboundedPageBurstOrInvalidIndex() {
        assertEquals(106f, ViewerFilmstripGeometry.fling(100f, -100000f, 48f, 500, false))
        assertEquals(94f, ViewerFilmstripGeometry.fling(100f, 100000f, 48f, 500, false))
        assertEquals(499f, ViewerFilmstripGeometry.fling(498f, -100000f, 48f, 500, false))
        assertEquals(100f, ViewerFilmstripGeometry.fling(100f, Float.NaN, 48f, 500, false))
    }
}
