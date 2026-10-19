package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppLockPolicyTest {
    private AppLockPolicy.Receipt receipt(int mode) { return new AppLockPolicy.Receipt(mode,1_700_000_000_000L,1000,12,"2026-10-19@Asia/Kolkata"); }
    private boolean permits(int mode,AppLockPolicy.Receipt receipt,long delta) { return AppLockPolicy.permits(mode,receipt,1_700_000_000_000L+delta,1000+delta,12,"2026-10-19@Asia/Kolkata"); }
    @Test public void offStillRequiresAnAuthenticatedReceiptForThisPolicy() {
        assertFalse(permits(0,null,0));
        assertFalse(permits(0,receipt(2),0));
        assertTrue(permits(0,receipt(0),10L*86400000));
    }
    @Test public void shortAndLongReceiptsExpireAtTheExactDeadline() {
        for(int mode:new int[]{2,5,15,30,60,120}) {
            assertTrue(permits(mode,receipt(mode),LockGrace.durationMillis(mode)-1));
            assertFalse(permits(mode,receipt(mode),LockGrace.durationMillis(mode)));
        }
    }
    @Test public void dailyUsesTheCalendarDayAndDoesNotBecomeARollingExtension() {
        var r=receipt(1440);
        assertTrue(permits(1440,r,1000));
        assertFalse(AppLockPolicy.permits(1440,r,r.wall()+1000,r.elapsed()+1000,12,"2026-10-20@Asia/Kolkata"));
        assertFalse(permits(1440,r,86400000));
    }
    @Test public void rollbackAndRebootCannotExtendTimedOrDailyReceipts() {
        for(int mode:new int[]{2,120,1440}) {
            var r=receipt(mode);
            assertFalse(permits(mode,r,-1));
            assertFalse(AppLockPolicy.permits(mode,r,r.wall()+1000,r.elapsed()-1,12,r.day()));
            assertFalse(AppLockPolicy.permits(mode,r,r.wall()+1000,r.elapsed()+1000,13,r.day()));
            assertFalse(AppLockPolicy.permits(mode,r,r.wall()+1000,r.elapsed()+1000,-1,r.day()));
        }
    }
    @Test public void changingPolicyCannotReuseAPreviousGrant() {
        assertFalse(permits(120,receipt(2),0));
        assertFalse(permits(2,receipt(120),0));
        assertFalse(permits(-50,receipt(120),0));
    }
    @Test public void timezoneChangesAndEitherClockReachingExpiryRequireVerification() {
        var r=receipt(1440);
        assertFalse(AppLockPolicy.permits(1440,r,r.wall(),r.elapsed(),12,"2026-10-19@UTC"));
        var shortReceipt=receipt(2);
        assertFalse(AppLockPolicy.permits(2,shortReceipt,shortReceipt.wall()+120000,shortReceipt.elapsed()+1,12,r.day()));
        assertFalse(AppLockPolicy.permits(2,shortReceipt,shortReceipt.wall()+1,shortReceipt.elapsed()+120000,12,r.day()));
    }
}
