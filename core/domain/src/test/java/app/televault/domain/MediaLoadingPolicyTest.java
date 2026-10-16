package app.televault.domain;

import static org.junit.Assert.*;
import org.junit.Test;

public class MediaLoadingPolicyTest {
    @Test public void photosAndShortOrUnknownVideosNeverGetAnIndicator() {
        assertFalse(MediaLoadingPolicy.mayShowIndicator("image/jpeg", 9999999));
        assertFalse(MediaLoadingPolicy.mayShowIndicator("video/mp4", 0));
        assertFalse(MediaLoadingPolicy.mayShowIndicator("video/mp4", 60000));
        assertFalse(MediaLoadingPolicy.mayShowIndicator("video/x-matroska", 120000));
        assertFalse(MediaLoadingPolicy.mayShowIndicator(null, 3600000));
        assertTrue(MediaLoadingPolicy.mayShowIndicator("video/x-matroska", 120001));
    }
}
