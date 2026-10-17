package app.televault.domain;

import java.time.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryHighlightsTest {
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");
    private MemoryHighlights.Photo photo(String id, String date) {
        return new MemoryHighlights.Photo(id, LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli());
    }
    @Test public void anniversariesUseLocalDatesAndPreferClosestDay() {
        var cards = MemoryHighlights.select(List.of(photo("near", "2025-10-16"), photo("exact", "2025-10-17"), photo("older", "2024-10-17")), LocalDate.parse("2026-10-17"), zone);
        assertEquals(2, cards.size());
        assertEquals(List.of("exact"), cards.get(0).ids());
        assertEquals(1, cards.get(0).yearsAgo());
        assertEquals(2, cards.get(1).yearsAgo());
    }
    @Test public void leapDayAndYearBoundaryAreCalendarSafe() {
        assertEquals(1, MemoryHighlights.select(List.of(photo("leap", "2023-02-28")), LocalDate.parse("2024-02-29"), zone).get(0).yearsAgo());
        assertEquals(List.of("dec"), MemoryHighlights.select(List.of(photo("dec", "2024-12-31")), LocalDate.parse("2026-01-01"), zone).get(0).ids());
    }
    @Test public void dayCollectionsDeduplicateAndRequireOldRealPhotos() {
        var photos = List.of(photo("a", "2024-06-01"), photo("b", "2024-06-01"), photo("c", "2024-06-01"), photo("a", "2024-06-01"), photo("recent", "2026-10-16"), new MemoryHighlights.Photo("invalid", 0));
        var cards = MemoryHighlights.select(photos, LocalDate.parse("2026-10-17"), zone);
        assertEquals(1, cards.size());
        assertEquals(0, cards.get(0).yearsAgo());
        assertEquals(List.of("a", "b", "c"), cards.get(0).ids());
    }
    @Test public void selectionsAreBoundedAndDoNotInventEmptyMemories() {
        assertTrue(MemoryHighlights.select(List.of(), LocalDate.now(zone), zone).isEmpty());
        var photos = new ArrayList<MemoryHighlights.Photo>();
        for (int i = 0; i < 100; i++) photos.add(photo("p" + i, "2025-10-17"));
        assertEquals(30, MemoryHighlights.select(photos, LocalDate.parse("2026-10-17"), zone).get(0).ids().size());
    }
}
