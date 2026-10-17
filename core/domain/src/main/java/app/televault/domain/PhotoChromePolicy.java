package app.televault.domain;

/** Downward browsing hides overlays; returning within the list reveals just its filter pill. */
public final class PhotoChromePolicy {
    public enum Mode { EXPANDED, HIDDEN, COMPACT }
    private float movement;
    private int direction;
    private Mode mode = Mode.EXPANDED;
    public Mode mode() { return mode; }
    public Mode scroll(float dy, boolean atTop) {
        if (!Float.isFinite(dy) || dy == 0) return mode;
        if (atTop && dy > 0) { reset(); return mode; }
        int next = dy > 0 ? 1 : -1;
        if (direction != next) movement = 0;
        direction = next;
        movement += Math.abs(dy);
        if (movement >= (next < 0 ? 12 : 28)) mode = next < 0 ? Mode.HIDDEN : Mode.COMPACT;
        return mode;
    }
    public void reset() { movement = 0; direction = 0; mode = Mode.EXPANDED; }
}
