package app.televault.domain;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoGesturePolicyTest {
    @Test public void sidesDoNotStealTheCentreFullscreenGesture() {
        assertEquals(VideoGesturePolicy.Zone.BRIGHTNESS, VideoGesturePolicy.zone(.2f));
        assertEquals(VideoGesturePolicy.Zone.FULLSCREEN, VideoGesturePolicy.zone(.5f));
        assertEquals(VideoGesturePolicy.Zone.VOLUME, VideoGesturePolicy.zone(.8f));
    }
    @Test public void upwardAndDownwardDragsRespectBrightnessAndVolumeBounds() {
        assertEquals(100, VideoGesturePolicy.level(50, -50, 100, false));
        assertEquals(150, VideoGesturePolicy.level(50, -50, 100, true));
        assertEquals(1, VideoGesturePolicy.level(50, 5000, 100, false));
        assertEquals(0, VideoGesturePolicy.level(50, 5000, 100, true));
        assertEquals(200, VideoGesturePolicy.level(50, -5000, 100, true));
    }
    @Test public void missingOrInvalidGeometryCannotCreateSpuriousVolumeChanges() {
        assertEquals(50, VideoGesturePolicy.level(50, Float.NaN, 100, true));
        assertEquals(50, VideoGesturePolicy.level(50, 500, 0, true));
        assertEquals(50, VideoGesturePolicy.level(50, 500, Float.POSITIVE_INFINITY, false));
    }
}
