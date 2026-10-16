package app.televault.domain;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** Finite pinch density with explicit calendar periods, including cross-year weeks. */
public final class TimelineZoom {
    private TimelineZoom() {}
    public enum Period { DAY, WEEK, MONTH }
    public static final int DAY_COLUMNS = 3;
    public static final int WEEK_COLUMNS = 4;
    public static final int MONTH_COLUMNS = 6;
    public static float pinch(float current, float factor) {
        if (!Float.isFinite(current)) current = DAY_COLUMNS;
        if (!Float.isFinite(factor) || factor <= 0) return clamp(current);
        return clamp(current / factor);
    }
    public static float clamp(float value) { return Float.isNaN(value) ? DAY_COLUMNS : Math.max(2, Math.min(MONTH_COLUMNS, value)); }
    public static int columns(float value) { return Math.round(clamp(value)); }
    public static Period period(float value) {
        int columns = columns(value);
        if (columns <= 3) return Period.DAY;
        if (columns <= 5) return Period.WEEK;
        return Period.MONTH;
    }
    public static boolean daily(float value) { return period(value) == Period.DAY; }
    public static LocalDate periodStart(long millis, float density, ZoneId zone) {
        LocalDate date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        return switch (period(density)) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }
    public static String aspect(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        int a = width, b = height;
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return (width / a) + ":" + (height / a);
    }
}
