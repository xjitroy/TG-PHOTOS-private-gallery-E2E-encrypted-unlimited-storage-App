package app.televault.domain;

import java.util.function.LongSupplier;

/** Process-local online throttling. Never destroys keys or claims to prevent offline guessing. */
public final class PasswordAttempts {
    private final LongSupplier clock;
    private int failures;
    private long retryAt;
    public PasswordAttempts(LongSupplier clock) { this.clock = clock; }
    public synchronized long remainingMillis() { return Math.max(0, retryAt - clock.getAsLong()); }
    public synchronized void failed() {
        failures = Math.min(9, failures + 1);
        if (failures >= 5) retryAt = clock.getAsLong() + Math.min(300000L, 30000L << (failures - 5));
    }
    public synchronized void succeeded() { failures = 0; retryAt = 0; }
}
