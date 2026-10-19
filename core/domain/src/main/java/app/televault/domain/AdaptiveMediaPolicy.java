package app.televault.domain;

/** Bounded work and retention; scroll/prefetch never count as an intentional view. */
public final class AdaptiveMediaPolicy {
    public static final long DAY = 86400000L;
    public static final long OPEN_DEBOUNCE = 30 * 1000L;
    public static final long VIDEO_WINDOW = 30 * 60 * 1000L;
    public static final long WARM_BUDGET = 256L * 1024 * 1024;
    public static final int PHOTO_BATCH = 50;
    public static final int WARM_COUNT = PHOTO_BATCH * 4;
    private AdaptiveMediaPolicy() { }
    public static int photoPercent(int index) {
        if (index < 0) return 0;
        return index < PHOTO_BATCH ? 80 : index < PHOTO_BATCH * 2 ? 60 : index < PHOTO_BATCH * 3 ? 40 : index < WARM_COUNT ? 20 : 0;
    }
    public static int nextViews(int previous, long last, long now) {
        if (now <= 0) return 0;
        if (last > now || last <= 0 || now - last >= 7 * DAY) return 1;
        return Math.min(16, Math.min(16, Math.max(1, previous)) + (now - last >= OPEN_DEBOUNCE ? 1 : 0));
    }
    public static long retention(boolean video, long duration, int views) {
        if (video) return views >= 2 ? 2 * DAY : DAY;
        return views >= 8 ? 7 * DAY : views >= 4 ? 3 * DAY : views >= 2 ? 2 * DAY : DAY;
    }
    public static long prefix(long size, int percent, long remaining) {
        if (size <= 0 || percent <= 0 || percent > 100 || remaining <= 0) return 0;
        long desired = size / 100 * percent + size % 100 * percent / 100;
        return Math.min(desired, remaining);
    }
    public static boolean shortVideo(long duration) { return duration > 0 && duration <= 120000L; }
}
