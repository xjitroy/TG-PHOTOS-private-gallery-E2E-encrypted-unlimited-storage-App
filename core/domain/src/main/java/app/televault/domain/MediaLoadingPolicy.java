package app.televault.domain;

/** Photos retain their thumbnail; only sustained buffering of known long videos needs an indicator. */
public final class MediaLoadingPolicy {
    public static final long VIDEO_INDICATOR_DELAY = 1800L;
    private MediaLoadingPolicy() { }
    public static boolean mayShowIndicator(String mime, long durationMillis) {
        return mime != null && mime.startsWith("video/") && durationMillis > 120000L;
    }
}
