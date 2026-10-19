package app.televault.domain;

/** Presentation allowance only; it never controls catalog or thumbnail restoration. */
public final class RestoreHintPolicy {
    private RestoreHintPolicy() { }
    public static final int MAX_SHOWS = 3;
    public static boolean canShow(int count) { return count >= 0 && count < MAX_SHOWS; }
}
