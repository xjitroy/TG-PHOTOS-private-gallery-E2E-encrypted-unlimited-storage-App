package app.televault.ui

import app.televault.domain.UniversalMediaSearch
import org.junit.Assert.*
import org.junit.Test

class SearchCalendarDatesTest {
    @Test fun malformedOrOutOfPickerYearCannotCrashCalendarOpening() {
        for (value in listOf("", "31/2/2026", "18/10/", "0001-01-01", "3000-01-01")) assertNull(SearchCalendarDates.utcMillis(value))
        assertTrue(UniversalMediaSearch.parse("", "3000-01-01", "3000-01-02").valid())
    }

    @Test fun nativeUtcSelectionHasNoLocalTimezoneDayShift() {
        val expected = java.time.Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()
        assertEquals(expected, SearchCalendarDates.utcMillis("১৮/১০/২০২৬"))
        assertEquals(expected, SearchCalendarDates.utcMillis("2026-10-18"))
        assertNotNull(SearchCalendarDates.utcMillis("1900-01-01"))
        assertNotNull(SearchCalendarDates.utcMillis("2100-12-31"))
    }
}
