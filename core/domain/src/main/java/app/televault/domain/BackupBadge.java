package app.televault.domain;

/** Capture time is deliberately unrelated to backup completion. Unknown legacy times stay hidden. */
public final class BackupBadge {
    public static final long DURATION = 6L * 60 * 60 * 1000;
    private BackupBadge() {}
    public static long completedAt(long catalogTime,long confirmedServerTime) {
        return confirmedServerTime>0?confirmedServerTime:Math.max(0,catalogTime);
    }
    public static boolean visible(long completedAt, long now) {
        return completedAt > 0 && now >= completedAt && now - completedAt < DURATION;
    }
}
