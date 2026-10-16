package app.televault.domain;

/** Minimum reconsideration period before app-controlled permanent deletion. */
public final class TrashCooldown {
    private TrashCooldown() {}
    public static final long MILLIS = 24L * 60 * 60 * 1000;
    public static long remaining(long trashedAt, long now, boolean enabled) {
        if (!enabled) return 0;
        if (trashedAt <= 0 || now < trashedAt) return MILLIS;
        return Math.max(0, MILLIS - (now - trashedAt));
    }
    public static boolean allowed(long trashedAt, long now, boolean enabled) {
        return trashedAt > 0 && remaining(trashedAt, now, enabled) == 0;
    }
}
