package app.televault.domain;

/** Conservative attempted-send budget, independently testable without Android/network access. */
public final class PhotoDataBudget {
    private PhotoDataBudget() {}
    public enum Block { NONE, DAILY_PHOTO_LIMIT, MOBILE_VIDEO_DISABLED }
    public record Decision(Block block, long reservedBytes) {}
    public static Decision reserve(String mime, boolean metered, boolean allowVideos, long limit, long used, long bytes, boolean override) {
        return reserve(mime, metered, allowVideos, limit, used, bytes, override, false);
    }
    public static Decision reserve(String mime, boolean metered, boolean allowVideos, long limit, long used, long bytes, boolean override, boolean budgetVideos) {
        if (mime == null || limit < 0 || used < 0 || bytes < 0) throw new IllegalArgumentException("Invalid budget");
        if (!metered) return new Decision(Block.NONE, used);
        if (mime.startsWith("video/") && !allowVideos) return new Decision(Block.MOBILE_VIDEO_DISABLED, used);
        if (!mime.startsWith("image/") && !(budgetVideos && mime.startsWith("video/"))) return new Decision(Block.NONE, used);
        if (!override && bytes > Math.max(0, limit-used)) return new Decision(Block.DAILY_PHOTO_LIMIT, used);
        long next = bytes > Long.MAX_VALUE-used ? Long.MAX_VALUE : used+bytes;
        return new Decision(Block.NONE, next);
    }
}
