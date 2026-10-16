package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityProgressTest {
    @Test public void waitingCompletedAndFailuresDoNotLookLikeActiveTransfers() {
        for (String phase : new String[]{"COMPLETE", "CANCELLED", "WAITING", "WAITING_UNLOCK", "RETRYING", "VIDEO_NOTICE_REQUIRED", "FAILED", "DAILY_PHOTO_LIMIT", "WAITING_TELEGRAM"}) assertFalse(ActivityProgress.active(phase));
        for (String phase : new String[]{"ENCRYPTING", "UPLOADING", "DOWNLOADING", "EXPORTING", "COMMITTING_INDEX", "MOVING_TO_TRASH"}) assertTrue(ActivityProgress.active(phase));
    }
    @Test public void actualProgressHandlesUnknownTotalsAndLargeFiles() {
        assertEquals(-1, ActivityProgress.percent(0, 0));
        assertEquals(50, ActivityProgress.percent(Long.MAX_VALUE / 2, Long.MAX_VALUE));
        assertEquals(0, ActivityProgress.percent(-10, 100));
        assertEquals(100, ActivityProgress.percent(1000, 100));
        assertEquals(33, ActivityProgress.percent(1, 3));
    }
}
