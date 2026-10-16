package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TimelineZoomTest {
    @Test public void pinchStaysBetweenDayAndMonthAndHandlesBadInput() {
        assertEquals(2, TimelineZoom.columns(TimelineZoom.pinch(3, 10)));
        assertEquals(6, TimelineZoom.columns(TimelineZoom.pinch(3, .01f)));
        assertTrue(TimelineZoom.daily(3));
        assertFalse(TimelineZoom.daily(4));
        assertEquals(3f, TimelineZoom.pinch(Float.NaN, Float.NaN), 0f);
        assertEquals(3f, TimelineZoom.pinch(3, 0), 0f);
        assertEquals(3f, TimelineZoom.pinch(3, Float.POSITIVE_INFINITY), 0f);
        assertEquals("4:3", TimelineZoom.aspect(4000, 3000));
        assertEquals("3:4", TimelineZoom.aspect(3000, 4000));
        assertEquals("", TimelineZoom.aspect(0, 4000));
    }
    @Test public void calendarPeriodsKeepWeeksTogetherAcrossYearAndLocalMidnight() {
        var zone = java.time.ZoneId.of("UTC");
        long sunday = java.time.Instant.parse("2023-01-01T12:00:00Z").toEpochMilli();
        assertEquals("2023-01-01", TimelineZoom.periodStart(sunday, 3, zone).toString());
        assertEquals("2022-12-26", TimelineZoom.periodStart(sunday, 4, zone).toString());
        assertEquals("2023-01-01", TimelineZoom.periodStart(sunday, 6, zone).toString());
        assertEquals("2023-01-01", TimelineZoom.periodStart(sunday, 10, zone).toString());
        long boundary = java.time.Instant.parse("2024-03-01T00:30:00Z").toEpochMilli();
        assertEquals("2024-02-01", TimelineZoom.periodStart(boundary, 6, java.time.ZoneId.of("America/Los_Angeles")).toString());
        assertEquals(TimelineZoom.Period.DAY, TimelineZoom.period(2));
        assertEquals(TimelineZoom.Period.WEEK, TimelineZoom.period(5));
        assertEquals(TimelineZoom.Period.MONTH, TimelineZoom.period(8));
        assertEquals(TimelineZoom.Period.MONTH, TimelineZoom.period(9));
    }

}
