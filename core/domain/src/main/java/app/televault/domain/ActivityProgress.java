package app.televault.domain;

/** Waiting/finished jobs never animate the header. Percentage is stage-specific, not simulated. */
public final class ActivityProgress {
    private ActivityProgress() {}
    public static boolean active(String phase) {
        return switch (phase) {
            case "VERIFYING_DEVICE", "ENCRYPTING", "UPLOADING", "DOWNLOADING", "EXPORTING", "COMMITTING_INDEX", "MOVING_TO_TRASH" -> true;
            default -> false;
        };
    }
    public static int percent(long completed, long total) {
        if (total <= 0) return -1;
        return (int) Math.floor(Math.max(0d, Math.min(1d, (double) completed / total)) * 100d);
    }
}
