package app.televault.domain;

/** A header can open only on a new drag begun at the first timeline item. */
public final class TopEdgeReveal {
    private final float threshold;
    private boolean armed;
    private float pulled;
    public TopEdgeReveal(float threshold) {
        if (!(threshold > 0) || !Float.isFinite(threshold)) throw new IllegalArgumentException("threshold");
        this.threshold = threshold;
    }
    public void beginGesture(boolean atTop) {
        armed = atTop;
        pulled = 0;
    }
    public boolean canReveal() { return armed; }
    public boolean pull(float available, boolean atTop) {
        if (!atTop || available < 0) cancelPull();
        if (armed && available > 0 && Float.isFinite(available)) pulled += available;
        return armed && pulled >= threshold;
    }
    public void cancelPull() { armed = false; pulled = 0; }
    public void endGesture() { cancelPull(); }
}
