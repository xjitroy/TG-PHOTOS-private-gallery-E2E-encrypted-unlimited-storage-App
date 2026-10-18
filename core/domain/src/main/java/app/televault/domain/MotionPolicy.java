package app.televault.domain;

/** Smart uses short transitions; explicit1x-4x and accessibility/system scaling remain respected. */
public final class MotionPolicy {
    private MotionPolicy() {}
    public static int mode(int value) { return value >= 0 && value <= 4 ? value : 0; }
    public static float multiplier(int mode) { int value = mode(mode); return value == 0 ? .8f : value; }
    public static float scale(int mode, float systemScale) {
        if (!Float.isFinite(systemScale) || systemScale < 0) systemScale = 1;
        return multiplier(mode) * Math.min(10f, systemScale);
    }
}
