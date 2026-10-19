package app.televault.domain;

import static org.junit.Assert.*;
import org.junit.Test;

public class RestoreHintPolicyTest {
    @Test public void onlyTheFirstThreePresentationsAreAdmitted() {
        for (int count = 0; count < 3; count++) assertTrue(RestoreHintPolicy.canShow(count));
        for (int count : new int[] {3, 4, 1000, Integer.MAX_VALUE}) assertFalse(RestoreHintPolicy.canShow(count));
    }
    @Test public void malformedCountsCannotRestartTheTourOrOverflow() {
        assertFalse(RestoreHintPolicy.canShow(-1));
        assertFalse(RestoreHintPolicy.canShow(Integer.MIN_VALUE));
    }
}
