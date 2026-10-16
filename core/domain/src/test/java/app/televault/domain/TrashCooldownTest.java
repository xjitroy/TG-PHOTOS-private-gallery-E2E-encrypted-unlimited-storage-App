package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrashCooldownTest {
    @Test public void requiresFullDayAndFailsClosedForMissingDateOrClockRollback() {
        long start=100000;
        assertFalse(TrashCooldown.allowed(start,start+TrashCooldown.MILLIS-1,true));
        assertTrue(TrashCooldown.allowed(start,start+TrashCooldown.MILLIS,true));
        assertFalse(TrashCooldown.allowed(0,Long.MAX_VALUE,true));
        assertEquals(TrashCooldown.MILLIS,TrashCooldown.remaining(start,start-1,true));
        assertTrue(TrashCooldown.allowed(start,start,false));
        assertFalse(TrashCooldown.allowed(0,start,false));
    }
}
