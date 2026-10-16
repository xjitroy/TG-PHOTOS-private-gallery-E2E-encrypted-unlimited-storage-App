package app.televault.domain;

/** Same-process background grace only; caller never persists an authenticated session. */
public final class LockGrace {
    private LockGrace() {}
    public static int minutes(int value) { return value == 5 ? 5 : 2; }
    public static long durationMillis(int value) { return minutes(value) * 60_000L; }
    public static boolean expired(long backgroundAt, long now, int value) {
        if (backgroundAt < 0) return false;
        return now < backgroundAt || now - backgroundAt >= durationMillis(value);
    }
}
