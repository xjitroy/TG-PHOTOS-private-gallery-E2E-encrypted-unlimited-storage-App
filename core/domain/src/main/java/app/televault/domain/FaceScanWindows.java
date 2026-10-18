package app.televault.domain;

import java.util.ArrayList;
import java.util.List;

/** Full frame plus overlapping views; keeps small faces visible to a 128px detector. */
public final class FaceScanWindows {
    private FaceScanWindows() { }
    public record Window(int left, int top, int width, int height) { }
    public static List<Window> forImage(int width, int height) {
        if (width <= 0 || height <= 0 || width > 10000 || height > 10000) throw new IllegalArgumentException("Invalid image size");
        var windows = new ArrayList<Window>();
        windows.add(new Window(0, 0, width, height));
        if (Math.max(width, height) <= 768) return List.copyOf(windows);
        int side = Math.max(512, (Math.max(width, height) + 3) / 4);
        int tileWidth = Math.min(width, side), tileHeight = Math.min(height, side);
        var xs = starts(width, tileWidth); var ys = starts(height, tileHeight);
        for (int y : ys) for (int x : xs) windows.add(new Window(x, y, tileWidth, tileHeight));
        return List.copyOf(windows);
    }
    private static List<Integer> starts(int length, int tile) {
        if (length <= tile) return List.of(0);
        int count = Math.min(6, 1 + (int)Math.ceil((length - tile) / (tile * .75)));
        var result = new ArrayList<Integer>();
        for (int i=0; i<count; i++) result.add((int)Math.round((double)i * (length - tile) / (count - 1)));
        return result;
    }
}
