package app.televault.domain;

/** Smart/1x are natural duration; larger choices are longer transitions, never FPS promises. */
public final class MotionPolicy {
    private MotionPolicy() {}
    public static int mode(int value) { return value >= 0 && value <= 4 ? value : 0; }
    public static float multiplier(int mode) { return Math.max(1, mode(mode)); }
    public static float scale(int mode, float systemScale) {
        if (!Float.isFinite(systemScale) || systemScale < 0) systemScale = 1;
        return multiplier(mode) * Math.min(10f, systemScale);
    }
}
