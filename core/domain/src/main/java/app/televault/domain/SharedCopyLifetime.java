package app.televault.domain;

/** Explicitly shared plaintext is temporary; a reversed wall clock fails closed. */
public final class SharedCopyLifetime {
    private SharedCopyLifetime() {}
    public static final long MILLIS = 60L * 60 * 1000;
    public static boolean expired(long createdAt, long now) {
        return createdAt <= 0 || now < createdAt || now - createdAt >= MILLIS;
    }
}
