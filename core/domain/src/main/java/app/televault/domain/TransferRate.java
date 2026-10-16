package app.televault.domain;

/** Monotonic-clock payload throughput. Unknown, paused or stalled transfers have no ETA. */
public final class TransferRate {
    private long time = -1;
    private long bytes;
    private long lastMovement;
    private double rate;

    public void sample(long now, long completed, boolean transferring) {
        if (!transferring || now < time || completed < bytes) {
            rate = 0;
            time = -1;
        }
        if (time < 0) {
            time = now;
            bytes = completed;
            lastMovement = now;
            return;
        }
        long elapsed = now - time;
        if (elapsed < 1000) return;
        long delta = completed - bytes;
        if (delta > 0) {
            double measured = delta * 1000d / elapsed;
            rate = rate == 0 ? measured : .25 * measured + .75 * rate;
            lastMovement = now;
        } else if (now - lastMovement >= 6000) {
            rate = 0;
        }
        time = now;
        bytes = completed;
    }

    public long bytesPerSecond() { return (long) rate; }
    public long secondsRemaining(long remaining, boolean known) {
        if (!known || remaining <= 0 || rate <= 0) return -1;
        return (long) Math.ceil(remaining / rate);
    }
}
