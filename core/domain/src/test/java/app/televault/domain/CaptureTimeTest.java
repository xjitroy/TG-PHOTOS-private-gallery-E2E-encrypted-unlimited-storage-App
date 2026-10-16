package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
public class CaptureTimeTest {
    @Test public void folderCopyModificationDoesNotReplaceOriginalCapture() {
        long captured = CaptureTime.exif("2024:02:29 10:20:30", "+06:00", "987", ZoneId.of("UTC"));
        long copied = Instant.parse("2026-10-11T10:20:30Z").toEpochMilli();
        assertEquals(captured, CaptureTime.backup(captured, copied, copied, copied + 1));
        assertEquals(captured, CaptureTime.backup(0, captured, copied, copied + 1));
        assertEquals(copied, CaptureTime.backup(0, 0, copied, copied + 1));
        assertEquals(copied + 1, CaptureTime.backup(0, 0, 0, copied + 1));
    }
    @Test public void providerSecondTimestampKeepsTimeAndCannotOverflow() {
        assertEquals(1735689601000L, CaptureTime.milliseconds(1735689601L));
        assertEquals(0, CaptureTime.milliseconds(-1));
        assertEquals(0, CaptureTime.milliseconds(Long.MAX_VALUE));
    }
    @Test public void videoCaptureUsesRecordedOffsetAndRejectsInvalidDate() {
        long expected = Instant.parse("2024-02-29T04:20:30.123Z").toEpochMilli();
        assertEquals(expected, CaptureTime.video("2024-02-29T10:20:30.123+06:00"));
        assertEquals(expected, CaptureTime.video("20240229T042030.123Z"));
        assertEquals(0, CaptureTime.video("20230229T042030.123Z"));
        assertEquals(0, CaptureTime.video("unknown"));
    }
    @Test public void originalOffsetAndFractionDetermineCloudTimelineDate() {
        assertEquals(Instant.parse("2024-02-29T04:20:30.123Z").toEpochMilli(),CaptureTime.exif("2024:02:29 10:20:30","+06:00","123",ZoneId.of("UTC")));
        assertEquals(Instant.parse("2024-02-29T10:20:30Z").toEpochMilli(),CaptureTime.exif("2024:02:29 10:20:30",null,null,ZoneId.of("UTC")));
        assertEquals(0,CaptureTime.exif("2023:02:29 10:20:30",null,null,ZoneId.of("UTC")));
        assertEquals(0,CaptureTime.exif("junk",null,null,ZoneId.of("UTC")));
    }
}
