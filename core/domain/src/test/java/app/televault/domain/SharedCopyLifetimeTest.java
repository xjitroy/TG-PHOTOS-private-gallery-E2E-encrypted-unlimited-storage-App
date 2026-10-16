package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharedCopyLifetimeTest {
    @Test public void temporaryCopyExpiresAtDeadlineAndInvalidClockFailsClosed() {
        long start = 10_000;
        assertFalse(SharedCopyLifetime.expired(start, start));
        assertFalse(SharedCopyLifetime.expired(start, start + SharedCopyLifetime.MILLIS - 1));
        assertTrue(SharedCopyLifetime.expired(start, start + SharedCopyLifetime.MILLIS));
        assertTrue(SharedCopyLifetime.expired(start, start - 1));
        assertTrue(SharedCopyLifetime.expired(0, start));
    }
}
