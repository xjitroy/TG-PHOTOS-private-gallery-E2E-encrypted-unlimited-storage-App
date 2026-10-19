package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class LockGraceTest {
    @Test public void offLongAndDailyChoicesRemainSeparateFromTheDefault() {
        assertFalse(LockGrace.expired(1,Long.MAX_VALUE,LockGrace.OFF));
        assertEquals(7200000L,LockGrace.durationMillis(120));
        assertEquals(86400000L,LockGrace.durationMillis(LockGrace.DAILY));
        for(int mode:new int[]{0,2,5,15,30,60,120,1440}) assertEquals(mode,LockGrace.minutes(mode));
    }
    @Test public void graceExpiresExactlyAtSelectedMonotonicDeadline() {
        assertFalse(LockGrace.expired(1000, 120999, 2));
        assertTrue(LockGrace.expired(1000, 121000, 2));
        assertFalse(LockGrace.expired(1000, 300999, 5));
        assertTrue(LockGrace.expired(1000, 301000, 5));
    }
    @Test public void invalidSettingAndClockRollbackFailSafely() {
        assertEquals(120000L, LockGrace.durationMillis(-1));
        assertEquals(120000L, LockGrace.durationMillis(Integer.MAX_VALUE));
        assertEquals(300000L, LockGrace.durationMillis(5));
        assertTrue(LockGrace.expired(1000, 999, 5));
        assertFalse(LockGrace.expired(-1, 9999999, 2));
    }
}
