package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class LockGraceTest {
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
