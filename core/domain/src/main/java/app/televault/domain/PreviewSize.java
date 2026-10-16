package app.televault.domain;

/** Aspect-preserving RAM preview budget. Originals and zoom regions are never resized on disk. */
public final class PreviewSize {
    public static final long PIXELS = 1024L * 1024;
    public record Size(int width, int height) {}
    public static Size fit(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid preview dimensions");
        if ((long) width * height <= PIXELS) return new Size(width, height);
        double scale = Math.sqrt((double) PIXELS / ((long) width * height));
        int w = Math.max(1, (int) (width * scale));
        int h = Math.max(1, (int) (height * scale));
        // Extremely thin images still respect the area budget after rounding a side to1.
        w = (int) Math.min(w, PIXELS);
        h = (int) Math.min(h, PIXELS);
        w = (int) Math.min(w, PIXELS / h);
        h = (int) Math.min(h, PIXELS / w);
        return new Size(w, h);
    }
    private PreviewSize() {}
}
