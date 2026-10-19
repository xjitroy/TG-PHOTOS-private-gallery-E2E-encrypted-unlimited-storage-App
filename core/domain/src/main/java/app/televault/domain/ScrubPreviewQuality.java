package app.televault.domain;

/** Screen-fit detail, bounded decoded RAM; percentages describe pixels, not JPEG quality. */
public final class ScrubPreviewQuality {
    private ScrubPreviewQuality() {}
    public record Size(int width, int height) {}
    public static Size fit(int width, int height, int viewportWidth, int viewportHeight, long pixelBudget, boolean adjacent) {
        return target(width, height, viewportWidth, viewportHeight, pixelBudget, adjacent, false);
    }
    /** Preserve enough source detail for center-crop tiles, without upscaling or unbounded panoramas. */
    public static Size cover(int width, int height, int viewportWidth, int viewportHeight, long pixelBudget) {
        return target(width, height, viewportWidth, viewportHeight, pixelBudget, false, true);
    }
    private static Size target(int width, int height, int viewportWidth, int viewportHeight, long pixelBudget, boolean adjacent, boolean cover) {
        if (width <= 0 || height <= 0 || viewportWidth <= 0 || viewportHeight <= 0 || pixelBudget <= 0) throw new IllegalArgumentException("Invalid preview geometry");
        double x = (double) viewportWidth / width, y = (double) viewportHeight / height;
        double scale = Math.min(1d, cover ? Math.max(x, y) : Math.min(x, y));
        if (adjacent) scale *= Math.sqrt(.5d);
        scale = Math.min(scale, Math.sqrt((double) pixelBudget / ((long) width * height)));
        int w = Math.max(1, (int) (width * scale));
        int h = Math.max(1, (int) (height * scale));
        w = (int) Math.min(w, pixelBudget);
        h = (int) Math.min(h, pixelBudget / w);
        return new Size(w, Math.max(1, h));
    }
    public static int sample(int width, int height, Size target) {
        int sample = 1;
        while (sample <= Integer.MAX_VALUE / 2 && Math.max(1, width / (sample * 2)) >= target.width && Math.max(1, height / (sample * 2)) >= target.height) sample *= 2;
        return sample;
    }
    public static int rotation(int exif) { return switch (exif) { case 3,4 -> 180; case 6,7 -> 90; case 5,8 -> 270; default -> 0; }; }
    public static boolean mirrored(int exif) { return exif == 2 || exif == 4 || exif == 5 || exif == 7; }
}
