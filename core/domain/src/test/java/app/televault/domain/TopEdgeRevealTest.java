package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TopEdgeRevealTest {
    @Test public void returningToTopNeedsAnotherGesture() {
        TopEdgeReveal edge = new TopEdgeReveal(88);
        edge.beginGesture(false);
        assertFalse(edge.canReveal());
        assertFalse(edge.pull(200, true));
        edge.endGesture();
        edge.beginGesture(true);
        assertTrue(edge.canReveal());
        assertFalse(edge.pull(87, true));
        assertTrue(edge.pull(1, true));
    }
    @Test public void flingAndDirectionChangesCannotReopenHeader() {
        TopEdgeReveal edge = new TopEdgeReveal(88);
        edge.beginGesture(true);
        edge.pull(40, true);
        edge.pull(-1, true);
        assertFalse(edge.canReveal());
        assertFalse(edge.pull(200, true));
        edge.beginGesture(true);
        edge.pull(200, false);
        assertFalse(edge.canReveal());
        edge.endGesture();
        assertFalse(edge.pull(200, true));
    }
    @Test public void separateSmallPullsDoNotAccumulate() {
        TopEdgeReveal edge = new TopEdgeReveal(88);
        edge.beginGesture(true);
        assertFalse(edge.pull(60, true));
        edge.endGesture();
        edge.beginGesture(true);
        assertFalse(edge.pull(60, true));
        assertFalse(edge.pull(Float.NaN, true));
    }
}
