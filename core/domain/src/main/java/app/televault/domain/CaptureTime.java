package app.televault.domain;
import java.time.*;
import java.time.format.*;

/** EXIF local capture time with optional recorded UTC offset; invalid tags use caller fallback. */
public final class CaptureTime {
    private CaptureTime() {}
    /** Upload time is only a last resort, never a substitute for an original timestamp. */
    public static long backup(long embedded, long queued, long source, long now) {
        if (embedded > 0) return embedded;
        if (queued > 0) return queued;
        if (source > 0) return source;
        return Math.max(0, now);
    }
    public static long milliseconds(long seconds) {
        return seconds > 0 && seconds <= Long.MAX_VALUE / 1000 ? seconds * 1000 : 0;
    }
    public static long video(String date) {
        if (date == null || date.length() > 64) return 0;
        try {
            return Math.max(0, Instant.parse(date).toEpochMilli());
        } catch (DateTimeException ignored) {
            try {
                var format = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSSX").withResolverStyle(ResolverStyle.STRICT);
                return Math.max(0, OffsetDateTime.parse(date, format).toInstant().toEpochMilli());
            } catch (DateTimeException invalid) { return 0; }
        }
    }
    public static long exif(String date, String offset, String fraction, ZoneId fallbackZone) {
        if (date == null || date.length() != 19) return 0;
        try {
            var format=DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
            var time=LocalDateTime.parse(date,format);
            if (fraction != null && fraction.matches("[0-9]{1,9}")) time=time.withNano(Integer.parseInt((fraction+"000000000").substring(0,9)));
            var zone=offset == null ? fallbackZone : ZoneOffset.of(offset);
            return Math.max(0,time.atZone(zone).toInstant().toEpochMilli());
        } catch(DateTimeException | NumberFormatException error) { return 0; }
    }
}
