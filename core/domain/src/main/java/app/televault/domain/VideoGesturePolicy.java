package app.televault.domain;

/** Side drags adjust playback presentation; the centre retains the existing fullscreen swipe. */
public final class VideoGesturePolicy {
    private VideoGesturePolicy() { }
    public enum Zone { BRIGHTNESS, FULLSCREEN, VOLUME }
    public static Zone zone(float fraction) { return fraction < .35f ? Zone.BRIGHTNESS : fraction > .65f ? Zone.VOLUME : Zone.FULLSCREEN; }
    public static int level(int initial, float distance, float height, boolean volume) {
        int minimum = volume ? 0 : 1, maximum = volume ? 200 : 100;
        if (!Float.isFinite(distance) || !Float.isFinite(height) || height <= 0) return Math.max(minimum, Math.min(maximum, initial));
        return Math.max(minimum, Math.min(maximum, Math.round(initial - distance / height * maximum)));
    }
}
