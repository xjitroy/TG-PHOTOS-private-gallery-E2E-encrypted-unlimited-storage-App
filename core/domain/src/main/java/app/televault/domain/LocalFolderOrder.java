package app.televault.domain;

/** Folder recency uses last modification even when a photo retains its old capture date. */
public final class LocalFolderOrder {
    private LocalFolderOrder() {}
    public static long updatedAt(long originalMillis, long modifiedSeconds) {
        long modified = modifiedSeconds <= 0 ? 0 : modifiedSeconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : modifiedSeconds * 1000;
        return Math.max(Math.max(0, originalMillis), modified);
    }
}
