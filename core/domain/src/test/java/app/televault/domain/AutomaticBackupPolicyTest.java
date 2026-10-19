package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.AutomaticBackupPolicy.*;
import static app.televault.domain.PhotoDataBudget.Block.*;

public class AutomaticBackupPolicyTest {
    @Test public void everyExtraIs200MbAndBaseAllowanceStaysSeparate() {
        assertEquals(250 * MIB, limitBytes(DEFAULT_MB, 0));
        long extra = 0;
        for (int press = 1; press <= 20; press++) {
            extra = addExtra(extra);
            assertEquals(200L * press, extra);
            assertEquals((250L + 200L * press) * MIB, limitBytes(250, extra));
        }
        assertTrue(limitBytes(100000, Long.MAX_VALUE) > 0);
        assertTrue(addExtra(Long.MAX_VALUE) > 0);
    }
    @Test public void cameraShortVideosSharePhotosAllowanceAndDoNotOverspend() {
        long limit = limitBytes(250, 0);
        var photo = reserve("image/jpeg", true, false, limit, 0, 200 * MIB, false, true, false);
        var video = reserve("video/mp4", true, false, limit, photo.reservedBytes(), 50 * MIB, false, true, false);
        assertEquals(NONE, video.block());
        assertEquals(limit, video.reservedBytes());
        assertEquals(DAILY_PHOTO_LIMIT, reserve("video/mp4", true, false, limit, video.reservedBytes(), 1, false, true, false).block());
        assertEquals(MOBILE_VIDEO_DISABLED, reserve("video/mp4", true, false, limit, 0, 1, false, false, false).block());
    }
    @Test public void manualIgnoresEveryMobileBudgetAndVideoRestriction() {
        for (String mime : new String[]{"image/jpeg", "video/mp4", "application/pdf", "audio/mpeg"}) {
            var decision = reserve(mime, true, false, 1, 1, 1000, false, true, true);
            assertEquals(NONE, decision.block());
            assertEquals(1, decision.reservedBytes());
        }
    }
    @Test public void unlimitedAndExistingVideoOverrideHaveExplicitDifferentScopes() {
        assertEquals(NONE, reserve("image/jpeg", true, false, 1, 1, 1000, true, true, false).block());
        assertEquals(NONE, reserve("video/mp4", true, true, 1, 1, 1000, false, true, false).block());
        assertEquals(DAILY_PHOTO_LIMIT, reserve("image/jpeg", true, true, 1, 1, 1, false, true, false).block());
        assertEquals(NONE, reserve("video/mp4", false, false, 1, 1, 1000, false, false, false).block());
    }
    @Test public void cameraDurationBoundariesUnknownDurationAndExplicitOverrides() {
        assertTrue(cameraVideoAllowed(1, 2, false, false));
        assertTrue(cameraVideoAllowed(120000, 2, false, false));
        assertFalse(cameraVideoAllowed(120001, 2, false, false));
        assertFalse(cameraVideoAllowed(0, 2, false, false));
        assertTrue(cameraVideoAllowed(3600000, 2, false, true));
        assertTrue(cameraVideoAllowed(3600000, 2, true, false));
        assertTrue(cameraVideoAllowed(3600000, 0, false, false));
        assertTrue(cameraVideoAllowed(300000, 5, false, false));
    }
    @Test public void wifiRetainsFullLengthAutomaticVideoBackup() {
        assertTrue(cameraVideoAllowed(3600000, 2, false, false, false));
        assertTrue(cameraVideoAllowed(0, 2, false, false, false));
        assertFalse(cameraVideoAllowed(120001, 2, false, false, true));
        assertTrue(cameraVideoAllowed(120001, 2, false, true, true));
    }
    @Test public void pendingPhotosPrecedeOlderAutomaticVideosButNeverManualRequests() {
        assertTrue(compare(false,"image/jpeg",200,"p",false,"video/mp4",1,"v",true)<0);
        assertTrue(compare(true,"video/mp4",300,"m",false,"image/jpeg",1,"p",true)<0);
        assertTrue(compare(false,"image/jpeg",200,"p",false,"video/mp4",1,"v",false)>0);
        assertTrue(compare(false,"image/jpeg",1,"a",false,"image/jpeg",1,"b",true)<0);
    }
}
