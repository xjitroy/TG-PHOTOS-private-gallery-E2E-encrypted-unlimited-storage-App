package app.televault.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar-day selection without UTC picker millis or fixed 24-hour arithmetic. */
public final class ExportDateRange {
    public static final long MIN_DAY = LocalDate.of(1970, 1, 2).toEpochDay();
    public static final long MAX_DAY = LocalDate.of(2100, 12, 31).toEpochDay();
    private ExportDateRange() {}
    private static long bounded(long day) { return Math.max(MIN_DAY, Math.min(MAX_DAY, day)); }
    public static long[] lastDays(long today, int count) {
        if (count < 1) throw new IllegalArgumentException("A date range needs at least one day");
        long end = bounded(today);
        return new long[]{bounded(end - (long)count + 1), end};
    }
    public static long[] thisMonth(long today) {
        LocalDate end = LocalDate.ofEpochDay(bounded(today));
        return new long[]{bounded(end.withDayOfMonth(1).toEpochDay()), end.toEpochDay()};
    }
    public static long[] initial(long from, long until, String zoneId, long today) {
        ZoneId zone = ZoneId.of(zoneId);
        long[] recent = lastDays(today, 30);
        long end = until > 0 ? bounded(Instant.ofEpochMilli(until - 1).atZone(zone).toLocalDate().toEpochDay()) : recent[1];
        long start = from > 0 ? bounded(Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toEpochDay()) : lastDays(end, 30)[0];
        if (until == 0) end = Math.max(end, start);
        if (start > end) start = end;
        return new long[]{start, end};
    }
    public static long[] window(long startDay, long endDay, String zoneId) {
        if (startDay < MIN_DAY || endDay > MAX_DAY || startDay > endDay) throw new IllegalArgumentException("Invalid calendar date range");
        ZoneId zone = ZoneId.of(zoneId);
        long from = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = LocalDate.ofEpochDay(endDay).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new long[]{from, until};
    }
}
