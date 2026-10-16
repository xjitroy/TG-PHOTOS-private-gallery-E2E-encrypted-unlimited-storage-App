package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class UploadQueuePolicyTest {
    @Test public void delayedRetriesDoNotBlockFreshAutomaticOrManualWakeups() {
        assertEquals("retry",UploadQueuePolicy.lane(false,false,30));
        assertEquals("automatic",UploadQueuePolicy.lane(false,false,0));
        assertEquals("manual",UploadQueuePolicy.lane(true,false,0));
        assertEquals("wifi",UploadQueuePolicy.lane(false,true,30));
    }
    @Test public void retryDelayIsBoundedAndStartsAtThirtySeconds() {
        assertEquals(30,UploadQueuePolicy.nextRetry(0));
        assertEquals(60,UploadQueuePolicy.nextRetry(30));
        assertEquals(900,UploadQueuePolicy.nextRetry(Long.MAX_VALUE));
    }
    @Test public void nextDayAllowanceResumesButOversizePhotoDoesNotSpin() {
        assertFalse(UploadQueuePolicy.photoAllowance(150,150,10,false));
        assertTrue(UploadQueuePolicy.photoAllowance(150,0,10,false));
        assertFalse(UploadQueuePolicy.photoAllowance(150,0,151,false));
        assertTrue(UploadQueuePolicy.photoAllowance(150,150,151,true));
    }
    @Test public void manualOvertakesQueuedAutomaticAndKeepsManualFifo() {
        assertTrue(UploadQueuePolicy.compare(true,200,"m",false,100,"a")<0);
        assertTrue(UploadQueuePolicy.compare(true,100,"a",true,200,"b")<0);
        assertTrue(UploadQueuePolicy.compare(false,100,"a",false,100,"b")<0);
    }
    @Test public void explicitRequestBypassesAutomaticPauseButNotMobileOrRetryRules() {
        assertTrue(UploadQueuePolicy.eligible("DATA_SAVER_AUTO_PAUSED",0,100,false,true,false,true,true,false,false));
        assertFalse(UploadQueuePolicy.eligible("DATA_SAVER_AUTO_PAUSED",0,100,false,false,false,true,true,false,false));
        assertFalse(UploadQueuePolicy.eligible("MOBILE_VIDEO_DISABLED",0,100,false,true,false,true,false,false,true));
        assertFalse(UploadQueuePolicy.eligible("WAITING",0,100,false,false,true,true,false,true,true));
        assertFalse(UploadQueuePolicy.eligible("WAITING_TELEGRAM",200,100,false,true,false,false,false,true,true));
    }
    @Test public void blockedManualDoesNotPreventEligibleAutomaticWorkAndTerminalIsExcluded() {
        assertFalse(UploadQueuePolicy.eligible("FAILED",0,100,false,true,false,false,false,true,true));
        assertFalse(UploadQueuePolicy.eligible("COMPLETE",0,100,false,false,false,false,false,true,true));
        assertTrue(UploadQueuePolicy.eligible("WAITING",0,100,false,false,false,false,false,true,true));
        assertTrue(UploadQueuePolicy.eligible("DAILY_PHOTO_LIMIT",0,100,false,false,false,false,false,true,true));
    }
}
