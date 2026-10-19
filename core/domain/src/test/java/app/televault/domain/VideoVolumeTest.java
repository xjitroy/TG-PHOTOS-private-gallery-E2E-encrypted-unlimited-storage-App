package app.televault.domain;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoVolumeTest {
    @Test public void defaultPlaybackIsBitExactAndLowSignalsCanBeDoubled() {
        for (float sample : new float[]{-1f, -.99f, 0f, .123f, .99f, 1f}) assertEquals(sample, VideoVolume.amplify(sample, 1f), 0f);
        assertEquals(.2f, VideoVolume.amplify(.1f, VideoVolume.gain(200)), .00001f);
        assertEquals(-.2f, VideoVolume.amplify(-.1f, VideoVolume.gain(200)), .00001f);
    }
    @Test public void boostedPeaksRemainFiniteAndNeverOverflowPcm() {
        for (float sample = -1f; sample <= 1f; sample += .001f) {
            float output = VideoVolume.amplify(sample, 2f);
            assertTrue(Float.isFinite(output) && output >= -1f && output <= 1f);
            assertTrue(sample == 0 || Math.signum(sample) == Math.signum(output));
        }
        assertTrue(VideoVolume.amplify(1f, 2f) < 1f);
    }
    @Test public void boundsCannotSilentlyEnableMoreThanTwoHundredPercent() {
        assertEquals(0, VideoVolume.percent(-100));
        assertEquals(200, VideoVolume.percent(Integer.MAX_VALUE));
        assertEquals(1f, VideoVolume.gain(0), 0f);
        assertEquals(1.5f, VideoVolume.gain(150), 0f);
        assertEquals(2f, VideoVolume.gain(10000), 0f);
    }
    @Test public void malformedFloatingSamplesCannotContaminateTheAudioBuffer() {
        assertEquals(0f, VideoVolume.amplify(Float.NaN, 2f), 0f);
        assertEquals(0f, VideoVolume.amplify(Float.POSITIVE_INFINITY, 2f), 0f);
        assertEquals(.5f, VideoVolume.amplify(.5f, Float.NaN), 0f);
    }
}
