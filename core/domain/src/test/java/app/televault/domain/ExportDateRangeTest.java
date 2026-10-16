package app.televault.domain;

import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class ExportDateRangeTest {
    private static long day(String date) { return LocalDate.parse(date).toEpochDay(); }
    @Test public void presetsIncludeTodayAndCrossLeapMonthCorrectly() {
        assertArrayEquals(new long[]{day("2024-02-24"),day("2024-03-01")}, ExportDateRange.lastDays(day("2024-03-01"),7));
        assertArrayEquals(new long[]{day("2024-02-01"),day("2024-03-01")}, ExportDateRange.lastDays(day("2024-03-01"),30));
        assertArrayEquals(new long[]{day("2024-02-01"),day("2024-02-29")}, ExportDateRange.thisMonth(day("2024-02-29")));
    }
    @Test public void selectedDatesRoundTripInSavedTimezone() {
        long[] window=ExportDateRange.window(day("2026-10-06"),day("2026-10-16"),"Asia/Dhaka");
        assertEquals(Instant.parse("2026-10-05T18:00:00Z").toEpochMilli(),window[0]);
        assertEquals(Instant.parse("2026-10-16T18:00:00Z").toEpochMilli(),window[1]);
        assertArrayEquals(new long[]{day("2026-10-06"),day("2026-10-16")},ExportDateRange.initial(window[0],window[1],"Asia/Dhaka",day("2026-11-01")));
    }
    @Test public void sameDayWindowIncludesWholeDayAcrossBothDstChanges() {
        long spring=day("2026-03-08"),fall=day("2026-11-01");
        long[] shortDay=ExportDateRange.window(spring,spring,"America/New_York");
        long[] longDay=ExportDateRange.window(fall,fall,"America/New_York");
        assertEquals(23*3600000L,shortDay[1]-shortDay[0]);
        assertEquals(25*3600000L,longDay[1]-longDay[0]);
    }
    @Test public void newSelectionHasCompleteThirtyDayDraftAndRestoresOpenRanges() {
        long today=day("2026-10-16");
        assertArrayEquals(new long[]{day("2026-09-17"),today},ExportDateRange.initial(0,0,"UTC",today));
        long until=Instant.parse("2026-10-07T00:00:00Z").toEpochMilli();
        assertArrayEquals(new long[]{day("2026-09-07"),day("2026-10-06")},ExportDateRange.initial(0,until,"UTC",today));
    }
    @Test public void invalidOrUnsupportedDaysCannotBecomeExportBounds() {
        assertThrows(IllegalArgumentException.class,()->ExportDateRange.window(day("2026-10-16"),day("2026-10-06"),"UTC"));
        assertThrows(IllegalArgumentException.class,()->ExportDateRange.window(ExportDateRange.MIN_DAY-1,ExportDateRange.MIN_DAY,"UTC"));
        assertThrows(IllegalArgumentException.class,()->ExportDateRange.window(ExportDateRange.MAX_DAY,ExportDateRange.MAX_DAY+1,"UTC"));
        assertThrows(IllegalArgumentException.class,()->ExportDateRange.lastDays(day("2026-10-16"),0));
    }
}
