package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoChromePolicyTest {
    @Test public void reverseScrollShowsOnlyCompactFiltersUntilTrueTop() {
        var policy = new PhotoChromePolicy();
        assertEquals(PhotoChromePolicy.Mode.HIDDEN, policy.scroll(-20, false));
        assertEquals(PhotoChromePolicy.Mode.COMPACT, policy.scroll(30, false));
        assertEquals(PhotoChromePolicy.Mode.EXPANDED, policy.scroll(2, true));
    }
    @Test public void jitterAndDirectionChangesDoNotFlashOverlays() {
        var policy = new PhotoChromePolicy();
        policy.scroll(-20, false);
        policy.scroll(10, false);
        policy.scroll(-1, false);
        assertEquals(PhotoChromePolicy.Mode.HIDDEN, policy.scroll(20, false));
        assertEquals(PhotoChromePolicy.Mode.COMPACT, policy.scroll(10, false));
    }
    @Test public void invalidMovementCannotRevealOrHideAnything() {
        var policy = new PhotoChromePolicy();
        policy.scroll(Float.NaN, false);
        policy.scroll(Float.POSITIVE_INFINITY, true);
        assertEquals(PhotoChromePolicy.Mode.EXPANDED, policy.scroll(0, false));
    }
    @Test public void newSectionRestoresInitialHeader() {
        var policy = new PhotoChromePolicy();
        policy.scroll(-20, false);
        policy.reset();
        assertEquals(PhotoChromePolicy.Mode.EXPANDED, policy.mode());
    }
}
