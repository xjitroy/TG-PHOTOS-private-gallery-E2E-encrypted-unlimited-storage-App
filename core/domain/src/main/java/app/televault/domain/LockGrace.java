package app.televault.domain;

/** Normal-app policy only. Private Vault and sensitive-action authorization are independent. */
public final class LockGrace {
    public static final int OFF = 0;
    public static final int DAILY = 1440;
    private LockGrace() {}
    public static int minutes(int value) { return switch (value) { case OFF,2,5,15,30,60,120,DAILY -> value; default -> 2; }; }
    public static long durationMillis(int value) { return minutes(value) * 60_000L; }
    public static boolean expired(long backgroundAt, long now, int value) {
        if (minutes(value) == OFF) return false;
        if (backgroundAt < 0) return false;
        return now < backgroundAt || now - backgroundAt >= durationMillis(value);
    }
}
