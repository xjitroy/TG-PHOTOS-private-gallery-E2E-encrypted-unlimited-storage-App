package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdaptiveMediaPolicyTest {
    @Test public void photoTiersHaveExactBoundariesAndNoUnboundedTail() {
        assertEquals(80, AdaptiveMediaPolicy.photoPercent(29));assertEquals(60, AdaptiveMediaPolicy.photoPercent(30));
        assertEquals(40, AdaptiveMediaPolicy.photoPercent(80));assertEquals(20, AdaptiveMediaPolicy.photoPercent(130));
        assertEquals(0, AdaptiveMediaPolicy.photoPercent(180));assertEquals(0, AdaptiveMediaPolicy.photoPercent(-1));
    }
    @Test public void rotationScrollAndRepeatedCallbacksDoNotManufactureHotness() {
        long now=1700000000000L;
        assertEquals(1, AdaptiveMediaPolicy.nextViews(0,0,now));
        assertEquals(1, AdaptiveMediaPolicy.nextViews(1,now,now+1000));
        assertEquals(2, AdaptiveMediaPolicy.nextViews(1,now,now+AdaptiveMediaPolicy.OPEN_DEBOUNCE));
        assertEquals(1, AdaptiveMediaPolicy.nextViews(16,now+1,now));
        assertEquals(1, AdaptiveMediaPolicy.nextViews(16,now,now+7*AdaptiveMediaPolicy.DAY));
    }
    @Test public void shortVideosNeverReceivePhotoSevenDayRetention() {
        assertTrue(AdaptiveMediaPolicy.shortVideo(120000));assertFalse(AdaptiveMediaPolicy.shortVideo(120001));assertFalse(AdaptiveMediaPolicy.shortVideo(0));
        assertEquals(AdaptiveMediaPolicy.DAY,AdaptiveMediaPolicy.retention(true,60000,1));
        assertEquals(2*AdaptiveMediaPolicy.DAY,AdaptiveMediaPolicy.retention(true,60000,16));
        assertEquals(7*AdaptiveMediaPolicy.DAY,AdaptiveMediaPolicy.retention(false,0,16));
    }
    @Test public void preloadCannotOverflowOrExceedRemainingBudget() {
        assertEquals(64,AdaptiveMediaPolicy.prefix(Long.MAX_VALUE,80,64));assertEquals(80,AdaptiveMediaPolicy.prefix(100,80,1000));
        assertEquals(0,AdaptiveMediaPolicy.prefix(100,0,1000));assertEquals(0,AdaptiveMediaPolicy.prefix(100,80,0));
    }
}
