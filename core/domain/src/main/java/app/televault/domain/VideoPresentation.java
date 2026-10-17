package app.televault.domain;

/** Near9:16 uses portrait controls; everything else keeps the movie player. */
public final class VideoPresentation {
    private VideoPresentation() {}
    public static boolean shortLayout(int width,int height,float pixelRatio) {
        if(width<=0 || height<=0 || !Float.isFinite(pixelRatio) || pixelRatio<=0)return false;
        double ratio=(double)width*pixelRatio/height;
        return ratio>=0.48 && ratio<=0.68;
    }
    /** A finished session starts fresh; unfinished movies retain their exact position. */
    public static long resumePosition(long position, long duration, boolean ended) {
        if (ended || position < 0 || duration <= 0 || position >= duration) return 0;
        return position;
    }
    public static boolean replayAfterEnd(int completedPlays){return completedPlays>=1 && completedPlays<3;}

    /** Portrait feed paging and unknown/reset frames never request a landscape Activity. */
    public static boolean landscapePlayback(boolean verticalFeed, boolean portrait, boolean fullscreen, boolean knownFrame) {
        return fullscreen && knownFrame && !verticalFeed && !portrait;
    }

    /** Movie gestures resize the same player; no seek, media change or restart is implied. */
    public static boolean fullscreenAfterSwipe(boolean fullscreen, float distanceY, float threshold) {
        if (!Float.isFinite(distanceY) || !Float.isFinite(threshold) || threshold <= 0) return fullscreen;
        if (distanceY <= -threshold) return true;
        if (distanceY >= threshold) return false;
        return fullscreen;
    }

    /** Fit a compact video while leaving usable space for the scrollable details below it. */
    public static float compactHeight(float width, float availableHeight, float aspectRatio) {
        if (!Float.isFinite(width) || !Float.isFinite(availableHeight) || width <= 0 || availableHeight <= 0) return 1;
        float ratio = Float.isFinite(aspectRatio) && aspectRatio > 0 ? aspectRatio : 16f / 9f;
        return Math.max(1, Math.min(availableHeight * .60f, Math.max(100, width / ratio)));
    }
}
