package app.televault.domain;

/** Keep fractional finger motion, but publish only actual grid-layout changes. */
public final class TimelinePinch {
    private float density;
    private int columns;
    public TimelinePinch(float initial) { density=TimelineZoom.clamp(initial);columns=TimelineZoom.columns(density); }
    public int update(float factor) {
        density=TimelineZoom.pinch(density,factor);
        int next=TimelineZoom.columns(density);
        if(next==columns)return 0;
        columns=next;return next;
    }
}
