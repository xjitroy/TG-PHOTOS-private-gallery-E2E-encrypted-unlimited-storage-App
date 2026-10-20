package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class TimelineFastScrollMapTest {
    @Test fun mapsRealMosaicAndDateRowsIncludingIntroduction() {
        val map = TimelineFastScrollMap(12, listOf(TimelineScrollSection(1, "October"), TimelineScrollSection(5, "September"), TimelineScrollSection(9, "August")))
        assertEquals(0, map.target(0f))
        assertEquals(11, map.target(1f))
        assertEquals("October", map.date(0))
        assertEquals("October", map.date(4))
        assertEquals("September", map.date(5))
        assertEquals("August", map.date(11))
        assertEquals(1f, map.fraction(11), 0f)
    }

    @Test fun malformedFractionsAndChangingEmptyLibrariesStayBounded() {
        val empty = TimelineFastScrollMap(0, emptyList())
        assertEquals(0, empty.target(1f))
        assertEquals("", empty.date(5))
        assertEquals(0f, empty.fraction(100), 0f)
        val map = TimelineFastScrollMap(100, listOf(TimelineScrollSection(0, "Today")))
        assertEquals(0, map.target(Float.NaN))
        assertEquals(0, map.target(Float.POSITIVE_INFINITY))
        assertEquals(0, map.target(-5f))
        assertEquals(99, map.target(5f))
        assertEquals(0f, map.fraction(-100), 0f)
        assertEquals(1f, map.fraction(1000), 0f)
    }

    @Test fun largeLibrarySeeksStayOrderedAndResolveTheCorrectDate() {
        val map = TimelineFastScrollMap(1_000_000, (0 until 1000).map { TimelineScrollSection(it * 1000, "Day $it") })
        var previous = -1
        repeat(10_001) { tick ->
            val target = map.target(tick / 10_000f)
            assertTrue(target >= previous)
            assertTrue(target in 0 until 1_000_000)
            assertEquals("Day ${target / 1000}", map.date(target))
            previous = target
        }
    }
}
