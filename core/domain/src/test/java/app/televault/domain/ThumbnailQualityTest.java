package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThumbnailQualityTest {
    @Test public void gridAndLargeMosaicStayAboveTinyPreviewResolution() {
        for (int columns = 1; columns <= 12; columns++) {
            assertTrue(ThumbnailQuality.gridPixels(columns, false) >= 192);
            assertEquals(640, ThumbnailQuality.gridPixels(columns, true));
        }
        assertEquals(960, ThumbnailQuality.gridPixels(1, false));
        assertEquals(640, ThumbnailQuality.gridPixels(2, false));
    }
    @Test public void cameraImageSamplingDoesNotDropBelowRequestedDetail() {
        int sample = ThumbnailQuality.sample(4000, 3000, 384);
        assertTrue(4000 / sample >= 384);
        assertTrue(4000 / sample < 768);
        assertEquals(sample, ThumbnailQuality.sample(3000, 4000, 384));
    }
    @Test public void smallOriginalIsNeverFurtherReducedToMeetLargerRequest() {
        assertEquals(1, ThumbnailQuality.sample(250, 180, 640));
        assertEquals(1, ThumbnailQuality.sample(0, 0, 384));
        assertEquals(1, ThumbnailQuality.sample(4000, 3000, 0));
    }
    @Test public void sampledEdgeBoundHoldsAcrossCameraSizesAndTargets() {
        for (int edge : new int[]{384, 640, 1080, 4000, 8000, Integer.MAX_VALUE}) {
            for (int target : new int[]{96, 192, 384, 640, 960}) {
                int sample = ThumbnailQuality.sample(edge, edge / 2, target);
                assertTrue(sample > 0 && (sample & (sample - 1)) == 0);
                assertTrue(edge < target || edge / sample >= target);
                assertTrue(sample == 1 || (long) edge / sample < (long) target * 2);
            }
        }
    }
    @Test public void lowQualityCompletionCannotReplaceSharperDisplayedPhoto() {
        assertFalse(ThumbnailQuality.preferNew(640, 480, 96, 72));
        assertFalse(ThumbnailQuality.preferNew(640, 480, 0, 0));
        assertTrue(ThumbnailQuality.preferNew(96, 72, 640, 480));
        assertTrue(ThumbnailQuality.preferNew(0, 0, 96, 72));
    }
    @Test public void resolutionComparisonCannotOverflowForLargeImages() {
        assertFalse(ThumbnailQuality.preferNew(100000, 80000, 640, 480));
        assertTrue(ThumbnailQuality.preferNew(640, 480, 100000, 80000));
    }
}
