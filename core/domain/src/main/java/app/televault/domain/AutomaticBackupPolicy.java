package app.televault.domain;

/** Camera defaults and bounded daily additions; explicit unlimited choices stay separate. */
public final class AutomaticBackupPolicy {
    private AutomaticBackupPolicy() {}
    public static final long DEFAULT_MB = 250;
    public static final long EXTRA_MB = 200;
    public static final long MIB = 1024L * 1024;
    private static final long MAX_MB = Long.MAX_VALUE / MIB;
    public static long addExtra(long extra) { return Math.min(MAX_MB - 100000, Math.max(0, extra)) + EXTRA_MB; }
    public static long limitBytes(long baseMb, long extraMb) {
        long base = Math.max(1, Math.min(100000, baseMb));
        return (base + Math.min(MAX_MB - base, Math.max(0, extraMb))) * MIB;
    }
    public static boolean cameraVideoAllowed(long durationMs, int minutes, boolean videoOverride, boolean unlimited) {
        return videoOverride || unlimited || minutes == 0 || durationMs > 0 && durationMs <= Math.max(1, Math.min(60, minutes)) * 60000L;
    }
    public static boolean cameraVideoAllowed(long durationMs, int minutes, boolean videoOverride, boolean unlimited, boolean metered) {
        return !metered || cameraVideoAllowed(durationMs, minutes, videoOverride, unlimited);
    }
    public static int priority(String mime) { return mime != null && mime.startsWith("image/") ? 0 : mime != null && mime.startsWith("video/") ? 2 : 1; }
    public static int compare(boolean manualA, String mimeA, long orderA, String idA, boolean manualB, String mimeB, long orderB, String idB, boolean photosFirst) {
        int rank = photosFirst && !manualA && !manualB ? priority(mimeA) - priority(mimeB) : 0;
        return rank != 0 ? rank : UploadQueuePolicy.compare(manualA, orderA, idA, manualB, orderB, idB);
    }
    public static PhotoDataBudget.Decision reserve(String mime, boolean metered, boolean mobileVideos, long limit, long used, long bytes, boolean unlimited, boolean camera, boolean manual) {
        if (manual) return new PhotoDataBudget.Decision(PhotoDataBudget.Block.NONE, used);
        return PhotoDataBudget.reserve(mime, metered, mobileVideos || camera || unlimited, limit, used, bytes, unlimited, camera && !mobileVideos);
    }
}
