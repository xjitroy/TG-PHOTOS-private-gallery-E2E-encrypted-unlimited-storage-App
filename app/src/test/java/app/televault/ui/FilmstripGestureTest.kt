package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class FilmstripGestureTest {
    @Test fun thousandIntermediatePositionsNeverMoveTheRealPagerUntilSettlement() {
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()
        var realPage = 0
        val gesture = FilmstripGesture(1500, {}, { previews += it }, {}, {
            commits += it
            realPage = it
        })
        gesture.begin(0f)
        repeat(1000) { gesture.drag(-48f, 48f, false) }
        assertEquals(0, realPage)
        assertTrue(commits.isEmpty())
        assertEquals(1000, previews.last())
        gesture.finish(gesture.position)
        gesture.finish(1f) // duplicated stop cannot commit again
        assertEquals(listOf(1000), commits)
        assertEquals(1000, realPage)
    }

    @Test fun interruptedGestureCannotCommitAndNewDragCanResumeItsVisiblePosition() {
        val commits = mutableListOf<Int>()
        val gesture = FilmstripGesture(500, {}, {}, {}, { commits += it })
        gesture.begin(10f)
        gesture.update(45.5f)
        gesture.cancel()
        gesture.finish(99f)
        assertTrue(commits.isEmpty())
        gesture.begin(gesture.position)
        assertEquals(45.5f, gesture.position)
        gesture.drag(-24f, 48f, false)
        gesture.finish(gesture.position)
        assertEquals(listOf(46), commits)
    }

    @Test fun badOrOutOfRangeInputCannotCommitInvalidMedia() {
        val commits = mutableListOf<Int>()
        val gesture = FilmstripGesture(4, {}, {}, {}, { commits += it })
        gesture.begin(1f)
        gesture.update(Float.NaN)
        assertEquals(1f, gesture.position)
        gesture.update(10000f)
        gesture.finish(gesture.position)
        assertEquals(listOf(3), commits)
    }
}
