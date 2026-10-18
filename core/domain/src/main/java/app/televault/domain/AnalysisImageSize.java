package app.televault.domain;

/** Entire original content, sampled only to cap native/RAM use; never a gallery thumbnail. */
public final class AnalysisImageSize {
    private AnalysisImageSize() { }
    public record Size(int width, int height) { }
    public static Size fit(int width, int height, int maxEdge, long maxPixels) {
        if (width <= 0 || height <= 0 || maxEdge < 1 || maxPixels < 1) throw new IllegalArgumentException("Invalid image bounds");
        double scale = Math.min(1, Math.min((double)maxEdge / Math.max(width,height), Math.sqrt((double)maxPixels / ((double)width*height))));
        return new Size(Math.max(1,(int)(width*scale)), Math.max(1,(int)(height*scale)));
    }
}
