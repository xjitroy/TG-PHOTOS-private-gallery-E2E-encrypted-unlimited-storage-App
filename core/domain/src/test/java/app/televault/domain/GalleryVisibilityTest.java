package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class GalleryVisibilityTest {
    @Test public void hidesAssetsAndHiddenPathsButKeepsNormalMedia() {
        assertFalse(GalleryVisibility.clean("image/png", "icon.png", "Pictures", 512, 512, 1200));
        assertFalse(GalleryVisibility.clean("image/jpeg", "1.jpg", "Pictures/.private", 1080, 1920, 9000));
        assertFalse(GalleryVisibility.clean("image/svg+xml", "vector.svg", "Download", 0, 0, 2000));
        assertFalse(GalleryVisibility.clean("image/png", "a.png", "Pictures", 64, 64, 400));
        assertTrue(GalleryVisibility.clean("image/jpeg", "IMG_1.jpg", "DCIM/Camera", 1080, 1920, 9000));
        assertTrue(GalleryVisibility.clean("image/heic", "Portrait.heic", "Pictures", 0, 0, 9000));
        assertTrue(GalleryVisibility.clean("video/mp4", "Movie.mp4", "Download", 0, 0, 9000));
        assertFalse(GalleryVisibility.clean("video/mp4", "empty.mp4", "Download", 0, 0, 0));
    }
}
