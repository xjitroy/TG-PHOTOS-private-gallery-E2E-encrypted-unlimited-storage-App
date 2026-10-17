package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
public class UniversalMediaSearchTest {
 @Test public void bengaliDateAndNameCombine() { var q=UniversalMediaSearch.parse("রয় ১৭/১০/২০২৬","","");assertTrue(q.valid());assertEquals(LocalDate.of(2026,10,17),q.start());assertEquals("রয়",q.text()); }
 @Test public void inclusiveRangeUsesDeviceCalendar() { var q=UniversalMediaSearch.parse("Roy 2026-10-01 2026-10-17","","");var zone=ZoneId.of("Asia/Kolkata");long date=LocalDate.of(2026,10,17).atTime(23,59).atZone(zone).toInstant().toEpochMilli();assertTrue(UniversalMediaSearch.matches(q,"song.mp3","Roy",date,zone));assertFalse(UniversalMediaSearch.matches(q,"other.mp3","",date,zone)); }
 @Test public void invalidReversedDatesNeverMatchAll() { assertFalse(UniversalMediaSearch.parse("2026-02-30","","").valid());assertFalse(UniversalMediaSearch.parse("","17/10/2026","1/10/2026").valid()); }
 @Test public void typesRemainIndependentFromLiteralNames() { var q=UniversalMediaSearch.parse("Holiday mix","","");assertTrue(UniversalMediaSearch.matches(q,"Holiday MIX.mkv","",0,ZoneId.of("UTC")));assertFalse(UniversalMediaSearch.matches(q,"holiday.jpg","",0,ZoneId.of("UTC"))); }
}
