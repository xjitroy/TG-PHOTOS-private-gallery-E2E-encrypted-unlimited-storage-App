package app.televault.domain;

/** Resolution follows tile geometry, never finger speed or selection gestures. */
public final class ThumbnailQuality {
    private ThumbnailQuality() { }
    public static int gridPixels(int columns, boolean largeMosaicTile) {
        if (largeMosaicTile) return 640;
        if (columns <= 1) return 960;
        if (columns == 2) return 640;
        return columns <= 4 ? 384 : 192;
    }
    /** Physical pixels, including a mosaic cell's row span; independent of scroll velocity. */
    public static int tilePixels(int width, int height, int minimum) {
        int edge = Math.max(Math.max(width, height), minimum);
        for (int tier : new int[]{192, 384, 640, 768, 960, 1024, 1280, 1536}) {
            if (edge <= tier) return tier;
        }
        return 1536;
    }
    public static boolean needsDetail(int width, int height, int bitmapWidth, int bitmapHeight) {
        return width > 0 && height > 0 && (bitmapWidth < width || bitmapHeight < height);
    }
    /** Use the largest power-of-two sample that does not undershoot the requested long edge. */
    public static int sample(int width, int height, int requested) {
        int edge = Math.max(width, height);
        if (edge <= 0 || requested <= 0) return 1;
        int sample = 1;
        while (sample <= Integer.MAX_VALUE / 2 && edge / (sample * 2) >= requested) sample *= 2;
        return sample;
    }
    public static boolean preferNew(int oldWidth, int oldHeight, int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) return false;
        return oldWidth <= 0 || oldHeight <= 0 || (long) newWidth * newHeight >= (long) oldWidth * oldHeight;
    }
    /** Small strip tiles must not pin screen-sized bitmaps; high-detail requests cannot reuse tiny tiers. */
    public static boolean reusableTier(int requested, int available) {
        return requested > 0 && available >= requested && available <= Math.max(384L, requested * 2L);
    }
}
