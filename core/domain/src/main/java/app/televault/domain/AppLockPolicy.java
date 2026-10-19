package app.televault.domain;

/** Local app-gate receipts; never authorize Telegram, Private Vault or sensitive file actions. */
public final class AppLockPolicy {
    private AppLockPolicy() {}
    public record Receipt(int mode, long wall, long elapsed, int boot, String day) {}
    public static boolean permits(int choice, Receipt receipt, long wall, long elapsed, int boot, String day) {
        int mode = LockGrace.minutes(choice);
        if (receipt == null || receipt.mode() != mode) return false;
        if (mode == LockGrace.OFF) return true;
        if (boot < 0 || receipt.boot() != boot || receipt.wall() <= 0 || receipt.elapsed() < 0 || wall < receipt.wall() || elapsed < receipt.elapsed()) return false;
        long duration = LockGrace.durationMillis(mode);
        if (wall - receipt.wall() >= duration || elapsed - receipt.elapsed() >= duration) return false;
        return mode != LockGrace.DAILY || (day != null && !day.isBlank() && day.equals(receipt.day()));
    }
}
