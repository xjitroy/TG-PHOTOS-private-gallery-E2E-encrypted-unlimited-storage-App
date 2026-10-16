package app.televault.domain;

/** A stable partial category grant differs from Android 14's changeable selected-photo scope. */
public final class DeviceIndexAccess {
    private DeviceIndexAccess() {}
    public static boolean limitedVisual(boolean allFiles, boolean images, boolean videos, boolean selectedPhotos) {
        return !allFiles && selectedPhotos && (!images || !videos);
    }
    public static boolean invalidate(String previous, String current, boolean limitedVisual) {
        return !previous.isEmpty() && (!previous.equals(current) || limitedVisual);
    }
}
