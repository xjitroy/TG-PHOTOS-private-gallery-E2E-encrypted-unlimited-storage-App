package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackupBadgeTest {
    @Test public void confirmedCatalogTimeWinsWithoutChangingTheOriginalCaptureDate() {
        assertEquals(2000,BackupBadge.completedAt(1000,2000));
        assertEquals(2000,BackupBadge.completedAt(0,2000));
    }
    @Test public void missingLegacyTimeIsNotInventedDuringRehydration() {
        assertEquals(0,BackupBadge.completedAt(0,0));
        assertEquals(1000,BackupBadge.completedAt(1000,0));
    }
    @Test public void expiresAtSixHoursAfterBackupRatherThanCapture() {
        long time=1800000000000L;
        assertTrue(BackupBadge.visible(time,time));
        assertTrue(BackupBadge.visible(time,time+BackupBadge.DURATION-1));
        assertFalse(BackupBadge.visible(time,time+BackupBadge.DURATION));
        assertFalse(BackupBadge.visible(time,time+BackupBadge.DURATION+1));
    }
    @Test public void unknownLegacyOrFutureTimeDoesNotShowMisleadingBadge() {
        assertFalse(BackupBadge.visible(0,1800000000000L));
        assertFalse(BackupBadge.visible(200,199));
        assertFalse(BackupBadge.visible(-1,200));
    }
}
