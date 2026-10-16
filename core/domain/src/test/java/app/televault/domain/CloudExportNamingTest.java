package app.televault.domain;

import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class CloudExportNamingTest {
    private static final String ID = "12345678-1234-1234-1234-123456789012";
    private static final long NOW = Instant.parse("2026-10-15T07:08:09Z").toEpochMilli();
    @Test public void selectedDaysUsePickerTimezoneNotUtcDay() {
        ZoneId zone = ZoneId.of("Asia/Dhaka");
        long from = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = LocalDate.of(2026, 10, 16).atStartOfDay(zone).toInstant().toEpochMilli();
        String name = CloudExportNaming.name(NOW, 1, 1, from, until, false, zone.getId(), from, until - 1, 123, 456789, ID);
        assertEquals("TG-Photos_Cloud_20261015_070809Z_Photos_Cam_original-20261001-20261015_123files_456789B_12345678.zip", name);
    }
    @Test public void allTimeShowsActualKnownCoverageWithoutInventingUnknownDates() {
        String known = CloudExportNaming.name(NOW, 2, 0, 0, 0, true, "UTC", NOW - 86400000, NOW, 2, 33, ID);
        assertTrue(known.contains("Videos_All_backup-all-20261014-20261015_2files_33B"));
        String unknown = CloudExportNaming.name(NOW, 0, 0, 0, 0, true, "UTC", 0, 0, 1, 0, ID);
        assertTrue(unknown.contains("all-unknown-unknown"));
    }
    @Test public void everyCategoryAndTypeFitsCommonFilesystemNameLimit() {
        String name = CloudExportNaming.name(NOW, 15, 4095, 0, 0, false, "UTC", NOW, NOW, Integer.MAX_VALUE, Long.MAX_VALUE, ID);
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 240);
        for (String token : new String[]{"Photos", "Videos", "Audio", "Files", "Cam", "Docs", "Shots", "ScreenRec", "IG", "WA", "TG", "FB", "Downloads", "PNG", "Other", "Movies"}) assertTrue(name.contains(token));
        assertFalse(name.contains("/"));
    }
    @Test public void daylightSavingEndDayIsInclusive() {
        ZoneId zone = ZoneId.of("America/New_York");
        long from = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli();
        assertEquals(25 * 3600000L, until - from);
        assertTrue(CloudExportNaming.name(NOW, 0, 4, from, until, false, zone.getId(), from, until - 1, 1, 1, ID).contains("Shots_original-20261101-20261101"));
    }
    @Test public void invalidRangesAndMasksAreRejectedBeforePersistingJob() {
        assertThrows(IllegalArgumentException.class, () -> CloudExportNaming.validate(16, 0, 0, 0, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> CloudExportNaming.validate(0, 4096, 0, 0, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> CloudExportNaming.validate(0, 0, 200, 100, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> CloudExportNaming.validate(0, 0, 100, 100, "UTC"));
    }
}
